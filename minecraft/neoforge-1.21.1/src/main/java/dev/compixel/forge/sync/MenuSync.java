package dev.compixel.forge.sync;

import com.mojang.logging.LogUtils;
import dev.compixel.sync.*;
import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionSubmission;
import dev.compixel.sync.state.SyncBatch;
import dev.compixel.sync.state.SyncLimits;
import dev.compixel.sync.state.SyncPublisher;
import dev.compixel.sync.state.SyncReceiver;
import dev.compixel.sync.state.SyncSchema;
import dev.compixel.sync.transport.TokenBucket;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** One binding per native menu. Configure before opening; all model work uses the game thread. */
public final class MenuSync<M extends AbstractContainerMenu> implements AutoCloseable {
    public enum Status {
        WAITING,
        SYNCING,
        READY,
        FAILED,
        CLOSED
    }

    public enum ActionStatus {
        APPLIED,
        REJECTED,
        INVALID,
        THROTTLED,
        EXPIRED
    }
    /** Sequence zero is a local refusal. actual/limit are -1 when unavailable. */
    public record ActionResult(
            long sequence,
            String action,
            ActionStatus status,
            ActionFailure failure,
            long actual,
            long limit,
            String detail) {}

    public record Statistics(
            Status status,
            long revision,
            long batches,
            long bytes,
            int largestBatch,
            double progress,
            String failure) {}

    final M model;
    final SyncSchema<M> schema;
    final MenuSyncOptions options;
    final SyncLimits limits;
    private SyncPublisher<M> publisher;
    private SyncReceiver<M> receiver;
    private Status status = Status.WAITING;
    private String failure = "";
    private long revision, batches, bytes;
    private int largestBatch;
    private double progress;
    private final Map<String, MenuAction<? super M, ?>> actions = new TreeMap<>();
    private boolean actionsFrozen;
    private String actionFingerprint;
    private ActionResult lastActionResult;
    private Consumer<M> onUpdate;
    private Consumer<ActionResult> onActionResult;
    private long lastWarning = Long.MIN_VALUE, suppressedWarnings;

    private MenuSync(M model, SyncSchema<M> schema, MenuSyncOptions options) {
        this.model = model;
        this.schema = Objects.requireNonNull(schema);
        this.options = Objects.requireNonNull(options);
        this.limits = options.state();
        if (limits.batchBytes() > MenuTransportLimits.MAX_STATE_BATCH_BYTES)
            throw new IllegalArgumentException(
                    "S2C batch exceeds Minecraft transport envelope: " + limits.batchBytes());
        if (options.actions().fragmentBytes() > MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES)
            throw new IllegalArgumentException("C2S fragment exceeds Minecraft transport envelope: "
                    + options.actions().fragmentBytes());
    }

    public static <M extends AbstractContainerMenu> MenuSync<M> bind(M menu, SyncSchema<M> schema) {
        return bind(menu, schema, MenuSyncOptions.DEFAULT);
    }

    public static <M extends AbstractContainerMenu> MenuSync<M> bind(
            M menu, SyncSchema<M> schema, MenuSyncOptions options) {
        return new MenuSync<>(menu, schema, options);
    }

    public MenuSyncOptions options() {
        return options;
    }

    public MenuSync<M> action(MenuAction<? super M, ?> action) {
        if (actionsFrozen || actions.putIfAbsent(action.id(), action) != null)
            throw new IllegalStateException("Late or duplicate menu action");
        actionFingerprint = null;
        return this;
    }

    public MenuSync<M> onUpdate(Consumer<M> listener) {
        if (actionsFrozen) throw new IllegalStateException("Late update listener");
        Objects.requireNonNull(listener);
        onUpdate = onUpdate == null ? listener : onUpdate.andThen(listener);
        return this;
    }
    /** Receives each local refusal and remote result, on the client game thread. */
    public MenuSync<M> onActionResult(Consumer<ActionResult> listener) {
        if (actionsFrozen) throw new IllegalStateException("Late action-result listener");
        Objects.requireNonNull(listener);
        onActionResult = onActionResult == null ? listener : onActionResult.andThen(listener);
        return this;
    }

    /** queued() means accepted into the local FIFO, never server acceptance. */
    public <V> ActionSubmission request(MenuAction<? super M, V> action, V value) {
        if (net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT)
            throw new IllegalStateException("Menu actions must be submitted on the client");
        Objects.requireNonNull(action);
        if (actions.get(action.id()) != action)
            return refuse(action.id(), ActionFailure.UNKNOWN_ACTION, -1, -1, "Action was not registered on this menu");
        var admission = ClientMenuSync.admission(this);
        if (admission != null)
            return refuse(
                    action.id(),
                    admission.failure(),
                    admission.actual(),
                    admission.limit(),
                    "Action admission refused");
        int available = (int) Math.min(Integer.MAX_VALUE, ClientMenuSync.remainingBytes(this));
        try {
            byte[] data = SyncRegistries.with(ClientMenuSync.registries(), () -> action.encode(value, available));
            var result = ClientMenuSync.sendAction(this, action.id(), data);
            if (!result.queued())
                return refuse(action.id(), result.failure(), result.actual(), result.limit(), "Action queue refused");
            return result;
        } catch (SizeLimitException oversized) {
            boolean queue = available < action.maximumBytes();
            long actual =
                    queue ? options.actions().maxQueuedBytes() - available + oversized.actual() : oversized.actual();
            return refuse(
                    action.id(),
                    queue ? ActionFailure.QUEUE_BYTES : ActionFailure.BODY_BYTES,
                    actual,
                    queue ? options.actions().maxQueuedBytes() : action.maximumBytes(),
                    oversized.getMessage());
        } catch (IOException invalid) {
            return refuse(action.id(), ActionFailure.CODEC, -1, -1, concise(invalid.getMessage()));
        }
    }

    private ActionSubmission refuse(String action, ActionFailure reason, long actual, long limit, String detail) {
        var result = ActionSubmission.rejected(reason, actual, limit);
        actionResult(
                new ActionResult(
                        0,
                        action,
                        reason == ActionFailure.BODY_BYTES || reason == ActionFailure.CODEC
                                ? ActionStatus.INVALID
                                : ActionStatus.REJECTED,
                        reason,
                        actual,
                        limit,
                        concise(detail)),
                ClientMenuSync.clock());
        return result;
    }

    public ActionResult lastActionResult() {
        return lastActionResult;
    }

    public boolean isSendingAction() {
        return net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT
                && ClientMenuSync.sending(this);
    }

    void actionResult(ActionResult result, long tick) {
        lastActionResult = result;
        warnAction(result, tick);
        if (onActionResult != null) onActionResult.accept(result);
    }

    void warnAction(ActionResult result, long tick) {
        long interval = options.rejectionLogIntervalTicks();
        if (result.status() == ActionStatus.APPLIED || interval < 0) return;
        if (lastWarning != Long.MIN_VALUE && tick - lastWarning < interval) {
            suppressedWarnings++;
            return;
        }
        lastWarning = tick;
        LogUtils.getLogger()
                .warn(
                        "Menu action refused: schema={}, action={}, sequence={}, status={}, reason={}, actual={}, limit={}, detail={}, suppressed={}",
                        schema.id(),
                        result.action(),
                        result.sequence(),
                        result.status(),
                        result.failure(),
                        result.actual(),
                        result.limit(),
                        result.detail(),
                        suppressedWarnings);
        suppressedWarnings = 0;
    }

    static String concise(String text) {
        if (text == null) return "";
        return text.substring(0, Math.min(256, text.length()))
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    ActionResult applyAction(String id, byte[] data, ServerPlayer player) {
        var action = actions.get(id);
        if (action == null)
            return new ActionResult(
                    0, id, ActionStatus.INVALID, ActionFailure.UNKNOWN_ACTION, -1, -1, "Unknown action");
        if (data.length > action.maximumBytes())
            return new ActionResult(
                    0,
                    id,
                    ActionStatus.INVALID,
                    ActionFailure.BODY_BYTES,
                    data.length,
                    action.maximumBytes(),
                    "Action body exceeds declaration");
        try {
            boolean applied = SyncRegistries.with(player.registryAccess(), () -> action.apply(model, player, data));
            return new ActionResult(
                    0,
                    id,
                    applied ? ActionStatus.APPLIED : ActionStatus.REJECTED,
                    applied ? ActionFailure.NONE : ActionFailure.REJECTED,
                    -1,
                    -1,
                    applied ? "" : "Consumer rejected the action");
        } catch (IOException invalid) {
            return new ActionResult(
                    0, id, ActionStatus.INVALID, ActionFailure.CODEC, data.length, -1, concise(invalid.getMessage()));
        }
    }

    int actionLimit(String id) {
        var action = actions.get(id);
        return action == null ? 0 : action.maximumBytes();
    }

    public Status status() {
        return status;
    }

    public boolean hasSnapshot() {
        return revision > 0 && status != Status.FAILED && status != Status.CLOSED;
    }

    public Statistics statistics() {
        return new Statistics(status, revision, batches, bytes, largestBatch, progress, failure);
    }

    public String schemaId() {
        return schema.id();
    }

    public String fingerprint() {
        if (actionFingerprint == null)
            try {
                var bytes = new java.io.ByteArrayOutputStream();
                try (var out = new java.io.DataOutputStream(bytes)) {
                    out.writeUTF(schema.fingerprint());
                    options.writePolicy(out);
                    out.writeInt(actions.size());
                    for (var entry : actions.entrySet()) {
                        out.writeUTF(entry.getKey());
                        out.writeUTF(entry.getValue().codecId());
                        out.writeInt(entry.getValue().maximumBytes());
                    }
                }
                actionFingerprint = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        return actionFingerprint;
    }

    void serverStart(TokenBucket bandwidth) {
        actionsFrozen = true;
        closeEngines();
        publisher = new SyncPublisher<>(schema, limits, bandwidth);
        status = Status.SYNCING;
        failure = "";
        revision = 0;
    }

    /** Native codecs encode with {@code registries}, the server's. */
    void serverPump(RegistryAccess registries, long tick, BooleanSupplier writable, Consumer<SyncBatch> send) {
        if (publisher == null || status == Status.FAILED) return;
        SyncRegistries.run(
                registries,
                () -> publisher.pump(model, tick, writable, batch -> {
                    count(batch);
                    status = Status.SYNCING;
                    send.accept(batch);
                }));
        revision = publisher.revision();
        if (revision > 0 && !publisher.busy()) status = Status.READY;
    }

    void acknowledge(long revision, int batch, long tick) {
        if (publisher != null) {
            publisher.acknowledge(revision, batch, tick);
            this.revision = publisher.revision();
            if (this.revision > 0 && !publisher.busy()) status = Status.READY;
        }
    }

    boolean serverExpired(long tick) {
        return publisher != null && publisher.expired(tick);
    }

    void clientStart() {
        actionsFrozen = true;
        closeEngines();
        receiver = new SyncReceiver<>(schema, limits);
        status = Status.SYNCING;
        failure = "";
        revision = 0;
        progress = 0;
        lastActionResult = null;
    }

    /** Native codecs decode with {@code registries}, the client's. */
    SyncReceiver.Result receive(RegistryAccess registries, SyncBatch batch, long tick) {
        if (receiver == null) throw new SyncException("No active receiver");
        var result = SyncRegistries.with(registries, () -> receiver.accept(model, batch, tick));
        if (result == SyncReceiver.Result.STAGED || result == SyncReceiver.Result.COMMITTED) count(batch);
        revision = receiver.revision();
        status = receiver.receiving() ? Status.SYNCING : Status.READY;
        progress = status == Status.READY ? 1 : receiver.progress();
        if (result == SyncReceiver.Result.COMMITTED && onUpdate != null) onUpdate.accept(model);
        return result;
    }

    boolean expired(long tick) {
        return receiver != null && receiver.expired(tick);
    }

    void fail(String reason) {
        closeEngines();
        failure = reason;
        status = Status.FAILED;
    }

    private void count(SyncBatch batch) {
        batches++;
        int length = batch.dataSize();
        bytes += length;
        largestBatch = Math.max(largestBatch, length);
    }

    private void closeEngines() {
        if (publisher != null) publisher.close();
        if (receiver != null) receiver.close();
        publisher = null;
        receiver = null;
    }

    @Override
    public void close() {
        closeEngines();
        status = Status.CLOSED;
    }
}
