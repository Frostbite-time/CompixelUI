package dev.compixel.sync.session;

import dev.compixel.sync.SyncException;
import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionResult;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.state.SyncBatch;
import dev.compixel.sync.state.SyncPublisher;
import dev.compixel.sync.state.SyncReceiver;
import dev.compixel.sync.state.SyncSchema;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Synchronizes one menu: the server sends its state as the schema describes, and the client sends the actions
 * registered with {@link #action}. Configure a binding before its menu opens; the sessions drive it on the game thread
 * of each side. Adapters subclass it to fix the menu and player types and to name the menu's network id.
 */
public abstract class SyncBinding<M, P, B extends SyncBinding<M, P, B>> implements AutoCloseable {
    /** Refused actions are logged at most once per this many ticks per menu. */
    private static final long WARNING_INTERVAL_TICKS = 100;

    private final M menu;
    private final SyncSchema<M> schema;
    private final SyncLog log;
    private SyncPublisher<M> publisher;
    private SyncReceiver<M> receiver;
    private SyncStatus status = SyncStatus.WAITING;
    private String failure = "";
    private long revision, batches, bytes;
    private int largestBatch;
    private double progress;
    private final Map<String, SyncAction<? super M, P, ?>> actions = new TreeMap<>();
    private boolean started;
    private String fingerprint;
    private ActionResult lastActionResult;
    private Consumer<M> onUpdate;
    private Consumer<ActionResult> onActionResult;
    private long lastWarning = Long.MIN_VALUE, suppressedWarnings;

    protected SyncBinding(M menu, SyncSchema<M> schema, SyncLog log) {
        this.menu = menu;
        this.schema = Objects.requireNonNull(schema);
        this.log = Objects.requireNonNull(log);
    }

    /** The menu's network id, which both sides put in their messages. */
    protected abstract int menuId();

    public final M menu() {
        return menu;
    }

    public final B action(SyncAction<? super M, P, ?> action) {
        if (started || actions.putIfAbsent(action.id(), action) != null)
            throw new IllegalStateException("Late or duplicate menu action");
        fingerprint = null;
        return self();
    }

    public final B onUpdate(Consumer<M> listener) {
        if (started) throw new IllegalStateException("Late update listener");
        Objects.requireNonNull(listener);
        onUpdate = onUpdate == null ? listener : onUpdate.andThen(listener);
        return self();
    }

    /** Receives each local refusal and remote result, on the client game thread. */
    public final B onActionResult(Consumer<ActionResult> listener) {
        if (started) throw new IllegalStateException("Late action-result listener");
        Objects.requireNonNull(listener);
        onActionResult = onActionResult == null ? listener : onActionResult.andThen(listener);
        return self();
    }

    public final ActionResult lastActionResult() {
        return lastActionResult;
    }

    public final SyncStatus status() {
        return status;
    }

    /** True once a complete state has arrived, and while later updates stream in. */
    public final boolean hasSnapshot() {
        return revision > 0 && status != SyncStatus.FAILED && status != SyncStatus.CLOSED;
    }

    public final SyncStatistics statistics() {
        return new SyncStatistics(status, revision, batches, bytes, largestBatch, progress, failure);
    }

    public final String schemaId() {
        return schema.id();
    }

    /** Identifies the schema and the declared actions; both sides must agree on it. */
    public final String fingerprint() {
        if (fingerprint == null)
            try {
                var bytes = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(bytes)) {
                    out.writeUTF(schema.fingerprint());
                    out.writeInt(actions.size());
                    for (var entry : actions.entrySet()) {
                        out.writeUTF(entry.getKey());
                        out.writeUTF(entry.getValue().codecId());
                        out.writeInt(entry.getValue().maximumBytes());
                    }
                }
                fingerprint = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        return fingerprint;
    }

    @Override
    public final void close() {
        closeEngines();
        status = SyncStatus.CLOSED;
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    final long revision() {
        return revision;
    }

    final SyncAction<? super M, P, ?> registered(String id) {
        return actions.get(id);
    }

    final int actionLimit(String id) {
        var action = actions.get(id);
        return action == null ? 0 : action.maximumBytes();
    }

    final void actionResult(ActionResult result, long tick) {
        lastActionResult = result;
        warnAction(result, tick);
        if (onActionResult != null) onActionResult.accept(result);
    }

    /** Logs a refused action, at most once per {@link #WARNING_INTERVAL_TICKS}. */
    final void warnAction(ActionResult result, long tick) {
        if (result.status() == ActionStatus.APPLIED) return;
        if (lastWarning != Long.MIN_VALUE && tick - lastWarning < WARNING_INTERVAL_TICKS) {
            suppressedWarnings++;
            return;
        }
        lastWarning = tick;
        log.warn(
                "Menu action refused: schema=" + schema.id() + ", action=" + result.action() + ", sequence="
                        + result.sequence() + ", status=" + result.status() + ", reason=" + result.failure()
                        + ", actual=" + result.actual() + ", limit=" + result.limit() + ", detail=" + result.detail()
                        + ", suppressed=" + suppressedWarnings,
                null);
        suppressedWarnings = 0;
    }

    /** Decodes and runs an action on the server. Handler exceptions propagate to the caller. */
    final ActionResult applyAction(String id, byte[] data, P player, CodecScope codecs) {
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
            boolean applied;
            try (var ignored = codecs.enter()) {
                applied = action.apply(menu, player, data);
            }
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

    final void serverStart() {
        started = true;
        closeEngines();
        publisher = new SyncPublisher<>(schema);
        status = SyncStatus.SYNCING;
        failure = "";
        revision = 0;
    }

    final void serverPump(CodecScope codecs, long tick, Consumer<SyncBatch> send) {
        if (publisher == null || status == SyncStatus.FAILED) return;
        try (var ignored = codecs.enter()) {
            publisher.pump(menu, tick, batch -> {
                count(batch);
                status = SyncStatus.SYNCING;
                send.accept(batch);
            });
        }
        revision = publisher.revision();
        if (revision > 0 && !publisher.busy()) status = SyncStatus.READY;
    }

    final void acknowledge(long revision, int batch) {
        if (publisher != null) {
            publisher.acknowledge(revision, batch);
            this.revision = publisher.revision();
            if (this.revision > 0 && !publisher.busy()) status = SyncStatus.READY;
        }
    }

    final void clientStart() {
        started = true;
        closeEngines();
        receiver = new SyncReceiver<>(schema);
        status = SyncStatus.SYNCING;
        failure = "";
        revision = 0;
        progress = 0;
        lastActionResult = null;
    }

    final SyncReceiver.Result receive(CodecScope codecs, SyncBatch batch) {
        if (receiver == null) throw new SyncException("No active receiver");
        SyncReceiver.Result result;
        try (var ignored = codecs.enter()) {
            result = receiver.accept(menu, batch);
        }
        if (result == SyncReceiver.Result.STAGED || result == SyncReceiver.Result.COMMITTED) count(batch);
        revision = receiver.revision();
        status = receiver.receiving() ? SyncStatus.SYNCING : SyncStatus.READY;
        progress = status == SyncStatus.READY ? 1 : receiver.progress();
        if (result == SyncReceiver.Result.COMMITTED && onUpdate != null) onUpdate.accept(menu);
        return result;
    }

    final void fail(String reason) {
        closeEngines();
        failure = reason;
        status = SyncStatus.FAILED;
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

    static String concise(String text) {
        if (text == null) return "";
        return text.substring(0, Math.min(256, text.length()))
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
