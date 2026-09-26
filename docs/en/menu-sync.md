# Menu synchronization

[简体中文](../zh-CN/menu-sync.md) · [Documentation](../README.md)

Declare the state a server menu exposes, then send typed requests for changes. Compose MC handles bounded batching, fragmentation, acknowledgements and complete client publication. Inventory ownership, permissions, persistence and resource semantics stay in the consumer.

Install matching Compose MC versions on **both client and server**, and declare a BOTH-side dependency. Keep UI classes in client entry points. The shared `menu-sync` core is dependency-free Java 17; game networking lives in the adapters. Examples use NeoForge 1.21.1.

## A synchronized menu

Implement `SyncedMenu` on your existing `AbstractContainerMenu` and keep one binding. This abstract example leaves native slot behavior and business operations to the concrete menu:

```java
import dev.composemc.sync.state.SyncCodecs;
import dev.composemc.sync.state.SyncSchema;
import dev.composemc.forge.sync.MenuAction;
import dev.composemc.forge.sync.MenuSync;
import dev.composemc.forge.sync.SyncedMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

abstract class PowerMenu extends AbstractContainerMenu implements SyncedMenu {
    private long energy;
    private boolean enabled;

    private static final SyncSchema<PowerMenu> SCHEMA =
        SyncSchema.<PowerMenu>builder("example:power", 1)
            .field("energy", SyncCodecs.LONG, m -> m.energy, (m, v) -> m.energy = v)
            .field("enabled", SyncCodecs.BOOLEAN, m -> m.enabled, (m, v) -> m.enabled = v)
            .build();

    private static final MenuAction<PowerMenu, Boolean> SET_ENABLED = MenuAction.of(
        "set.enabled", SyncCodecs.BOOLEAN,
        (menu, player, value) -> menu.mayConfigure(player) && menu.applyEnabled(value));

    private final MenuSync<PowerMenu> sync = MenuSync.bind(this, SCHEMA).action(SET_ENABLED);

    protected PowerMenu(MenuType<?> type, int id) { super(type, id); }
    @Override public MenuSync<?> menuSync() { return sync; }

    public void captureMachine(long energy, boolean enabled) {
        this.energy = energy;
        this.enabled = enabled;
    }
    public boolean requestEnabled(boolean value) { return sync.request(SET_ENABLED, value).queued(); }

    protected abstract boolean mayConfigure(ServerPlayer player);
    protected abstract boolean applyEnabled(boolean value);
}
```

Call `captureMachine` from server business updates before the menu broadcasts changes. Implement `applyEnabled` to validate and persist the requested change. Native menu lifecycle starts synchronization; do not build a second snapshot channel. A client controller calls `requestEnabled` on its game thread, for example while draining `UiBinding` actions. The returned Boolean means **queued**, not applied.

## Snapshot contracts

Getters must return non-null immutable values, including valid client defaults. Replace changed values and reuse unchanged references. In-place mutation is not a supported change signal. Setters run on the client game thread after a complete update and should perform plain assignments. If a setter fails, restoration is attempted; arbitrary external side effects cannot be rolled back.

`keyedCollection` handles immutable lists with stable unique keys. It synchronizes membership and values, not iteration-order-only changes. Keep order as explicit data or sort the client view. Changed large lists still require O(n) capture/materialization work.

For large collections with frequent small changes, use `SyncMap` and `keyedMap`:

```java
import dev.composemc.sync.state.*;

final class CatalogState {
    record Entry(int id, String name) {}
    private SyncMap<Integer, Entry> entries = SyncMap.empty();
    private static final SyncCodec<String> NAME = SyncCodecs.string(256);
    private static final SyncCodec<Entry> ENTRY = SyncCodec.of("example:entry/1",
        (out, entry) -> { out.writeInt(entry.id()); NAME.write(out, entry.name()); },
        in -> new Entry(in.readInt(), NAME.read(in)));

    static final SyncSchema<CatalogState> SCHEMA = SyncSchema.<CatalogState>builder("example:catalog", 1)
        .keyedMap("entries", SyncCodecs.INT, ENTRY, Entry::id,
            m -> m.entries, (m, values) -> m.entries = values)
        .build();

    void upsert(int id, String name) { entries = entries.with(id, new Entry(id, name)); }
    void remove(int id) { entries = entries.without(id); }
}
```

Retain the returned map: existing snapshots stay unchanged. Shared tree branches reduce diff/copy work; equal-hash keys use collision buckets. Iteration order is unspecified. `current.forEachChange(previous, handler)` reports added/changed/removed entries (`before` or `after` is null on insertion/removal). Updating derived search or sort indexes efficiently remains the consumer's job.

`include(prefix, schema)` and its projection overload compose declarations. Included identity/version participates in the handshake. Register `onUpdate(menu -> …)` before the session starts to derive a view after all fields commit. It runs before the final acknowledgement, never on partial batches, and is not a rollback transaction.

Field indexes use 32-bit framing; there is no fixed 64-field schema cap. Identifier syntax remains explicit: schema and codec IDs are at most 128 characters, field/action names at most 64, and inclusion prefixes at most 32. Action/field names use letters, digits, `_`, `.` and `-`; a schema ID uses a lowercase `namespace:path`. These are wire identities, separate from translated UI labels. The built-in enum codec represents up to 256 constants; use a custom codec for a larger value space.

## Codecs and native values

Built-ins include `INT`, `LONG`, `BOOLEAN`, `UUID`, `string(maxUtf8Bytes)` and `enumeration(EnumClass.class)`. Custom `SyncCodec.of(id, writer, reader)` codecs use `DataInput`/`DataOutput`; validate lengths and values before allocation. String limits count UTF-8 bytes. Change codec identity/schema version when wire meaning changes.

Modern adapters expose `MinecraftSyncCodecs.registry(id, maxBytes, registrySupplier, streamCodec)` for native registry-aware values. Use it on the owning game thread and choose record/action limits that cover framing overhead. Forge 1.20.1 instead provides `MinecraftSyncCodecs.buffer(id, maxBytes, PacketCodec<T>)` over `FriendlyByteBuf`. These helpers bound serialization; they do not remove restrictions inside a native codec.

## Actions and authority

Register matching action IDs/codecs before opening the menu. The adapter checks active menu identity, session token, sequence, initial acknowledgement, player liveness, spectator state and `stillValid`. Handlers still check permissions, ownership and business constraints before mutation. Action registration has no fixed 32-action cap.

`request(action, value)` returns an `ActionSubmission`. `queued()` means the local queue accepted the request; transmission and server execution may still be pending. A refusal contains `failure()`, `actual()` and `limit()`; its sequence is zero, nothing is sent and no sequence is consumed. Queued actions retain FIFO order and are never silently overwritten or automatically replayed.

| Failure | Meaning and response |
| --- | --- |
| `QUEUE_BYTES` | Retained outgoing bodies reached the consumer's byte budget. Wait or raise the configured budget. |
| `PENDING_ACTIONS` | Too many requests still await results. Wait for replies. |
| `BODY_BYTES` | Encoding exceeded this action's declared maximum. Change the data or the action declaration. |
| `CODEC` | A codec rejected the value, possibly under its own size budget. Read the result detail/log. |
| `NOT_READY`, `CLOSED`, `TRANSPORT_UNAVAILABLE`, `UNKNOWN_ACTION` | The menu/connection/action cannot accept the request. |

Handle refusal at the call site instead of assuming a click succeeded. This helper accepts your own feedback callback and keeps the full result available to the caller:

```java
import dev.composemc.forge.sync.MenuAction;
import dev.composemc.forge.sync.MenuSync;
import dev.composemc.sync.action.ActionSubmission;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.function.Consumer;

final class ActionRequests {
    static <M extends AbstractContainerMenu, V> ActionSubmission submit(
            MenuSync<M> sync, MenuAction<? super M, V> action, V value,
            Consumer<ActionSubmission> showRefusal) {
        var result = sync.request(action, value);
        if (!result.queued()) showRefusal.accept(result);
        return result;
    }
}
```

Choose localized feedback using `failure()`; include `actual()` and `limit()` when useful. Listen for the eventual result through `onActionResult` as well. A full queue does not erase earlier requests, and the library does not retain the refused click for a later retry.

Size values are encoded bytes before Minecraft compression. For an encoding stopped early, `actual` is the attempted size known at rejection, not necessarily the complete size. An exhausted queue may report its current retained size. Unknown values are `-1`; pending-count failures use counts instead of bytes.

`MenuAction.of(id, codec, handler)` defaults to 8 KiB. The overload with `maximumBytes` accepts any positive `int`; there is no library-imposed 2 MiB ceiling. A large logical body is fragmented into transport-safe packets. Its full encoded body must fit the configured queue, and its codec may have an independent limit. Native codec bridges likewise accept a consumer-selected positive byte limit; restrictions inside Minecraft's native codecs still apply.

Read `lastActionResult()` for the most recent local refusal or remote result. `ActionResult` includes sequence, action, status, failure reason, actual value, limit and detail. Remote statuses are `APPLIED`, `REJECTED`, `INVALID`, `THROTTLED` or `EXPIRED`; authoritative values arrive through ordinary state sync. Register `onActionResult(listener)` before opening to receive every result on the client game thread, even if several arrive in one tick. The library does not choose a popup/chat presentation for consumers.

Rejections produce a WARN by default. The log includes schema, action, sequence, reason, relevant budget and a short diagnostic; it does not dump the encoded body. `rejectionLogIntervalTicks` defaults to 100 ticks per binding, with a suppressed-count summary on the next emitted warning. Set it to `0` for every rejection or `-1` to disable these warnings. Result callbacks are not throttled.

Native inventory executors must honor `isSendingAction()` while outbound fragments remain queued. Closing/replacement/disconnect clears pending actions and assemblies. A local action deadline fails the binding and requires reopening, because dropping an assigned sequence and replaying later business commands would be ambiguous.

## Configure state and action budgets

Pass `MenuSyncOptions` as the third argument of `MenuSync.bind`. All application budgets below are consumer-configurable. `SyncLimits` controls S2C state; `ActionLimits` controls C2S actions; `TransferBudget` controls sustained and burst transmission in either direction.

```java
import dev.composemc.sync.MenuSyncOptions;
import dev.composemc.sync.action.ActionLimits;
import dev.composemc.sync.state.SyncLimits;
import dev.composemc.sync.transport.TransferBudget;

final class MenuBudgets {
    static final SyncLimits STATE = new SyncLimits(
        128 * 1024,
        new TransferBudget(32 * 1024, 4L * 1024 * 1024, 256 * 1024),
        16, 2 * 1024 * 1024, 64L * 1024 * 1024, 250_000, 400);

    static final ActionLimits ACTIONS = new ActionLimits(
        16 * 1024,
        new TransferBudget(64 * 1024, 4L * 1024 * 1024, 256 * 1024),
        16L * 1024 * 1024, 64, 32, 600, 200, 200);

    static final MenuSyncOptions OPTIONS = new MenuSyncOptions(STATE, ACTIONS, 100);
}
```

Use `MenuSync.bind(menu, schema, MenuBudgets.OPTIONS)`. The example selects larger consumer budgets; it is not the library default. For a smaller change, use `MenuSyncOptions.DEFAULT.withState(...)`, `.withActions(...)` or `.withRejectionLogInterval(...)`.

| S2C: `SyncLimits` component | Default | Scope |
| --- | --- | --- |
| `batchBytes` | 128 KiB | Maximum data per batch; smaller batches send immediately |
| `bandwidth.bytesPerTick` | 32 KiB | Sustained refill per server tick |
| `bandwidth.burstBytes` | 4 MiB | Maximum saved credit |
| `bandwidth.maxBytesPerTick` | 256 KiB | Peak emission per server tick |
| `inFlightBatches` | 16 | Batches awaiting client ACK |
| `maxRecordBytes` | 1 MiB | One encoded state operation |
| `maxUpdateBytes` | 32 MiB | One initial snapshot or delta, including record-length framing |
| `maxEntries` | 100,000 | Combined entries across synchronized collections |
| `timeoutTicks` | 200 | Startup / state transfer without progress |

| C2S: `ActionLimits` component | Default | Scope |
| --- | --- | --- |
| `fragmentBytes` | 16 KiB | Maximum body per action packet/fragment |
| `bandwidth.bytesPerTick` | 32 KiB | Sustained refill per client tick |
| `bandwidth.burstBytes` | 4 MiB | Maximum saved credit |
| `bandwidth.maxBytesPerTick` | 256 KiB | Peak emission per client tick |
| `maxQueuedBytes` | 4 MiB | Whole encoded bodies not yet completely sent |
| `maxPendingActions` | 32 | Queued, sending and awaiting-result requests combined |
| `actionsPerTick` | 16 | Client starts / server admissions per menu per tick |
| `queueTimeoutTicks` | 200 | Waiting from enqueue until the first bytes are sent |
| `progressTimeoutTicks` | 200 | No send progress, or no new fragment at server assembly |
| `replyTimeoutTicks` | 200 | Waiting for a result after the whole body has been sent |

The sender retains a whole body until its final fragment is sent, so `maxQueuedBytes` includes already-sent portions of partially sent bodies. New submissions are refused when the byte/count budget cannot accommodate them; existing requests continue. Once fully sent, body memory is released while the pending-result entry remains. A long transfer that keeps making progress does not consume its reply deadline in advance.

Byte/count/rate values must be positive and fit the API's Java numeric types. A state record needs at least five bytes for its field index and operation kind; its configured maximum plus the four-byte length must fit both a Java byte array and the update budget. There are no separate arbitrary ceilings such as a 256 MiB update maximum or one-million-entry maximum. These are protocol bounds, not heap-size or performance guarantees; codecs and handlers remain responsible for their own work and allocations.

Minecraft framing still imposes physical packet constraints. The adapter publishes `MenuTransportLimits.MAX_STATE_BATCH_BYTES` (1 MiB minus 256 bytes) and `MAX_ACTION_FRAGMENT_BYTES` (32,767 minus 256 bytes). The reservation covers packet identification and our envelope across supported loaders. `bind` rejects larger batch/fragment settings before launch. Logical records/actions may exceed those sizes because the library owns fragmentation; it does not rely on a loader's automatic splitter.

## Sustained traffic and bursts

`TransferBudget(refill, capacity, peak)` implements a token bucket. It starts full, refills during idle time up to capacity, spends credit when emitting bytes, and applies the peak ceiling even when abundant credit is saved. ACK capacity and Netty writability can pause it sooner. Maximum packet size is not a minimum send size: a small update or a partially replenished account can send a short packet without waiting for a full batch.

Adapters retain separate S2C and C2S credit accounts for the real connection. Closing, reopening or switching menus does not replenish them. Changing a menu policy refills under the previous policy up to the change, then clamps existing credit to the new capacity. Reconnecting starts a new account. This avoids repeatedly granting a new burst when navigating menus.

With the defaults at 20 TPS, sustained refill is 640 KiB/s and peak emission is 5 MiB/s while credit lasts. A 1 MiB burst can be emitted in four ticks when credit, ACKs and the connection permit; continuous traffic eventually returns to sustained refill. These are encoded-body rates before compression, not measured network throughput or total server bandwidth limits. `TransferBudget.steady(bytesPerTick)` disables saved multi-tick bursts.

State and action policies, including per-action body limits, participate in the handshake. Declare matching values on both sides before constructing/opening the menu; a consumer exposing runtime settings must distribute the selected policy to its client too. Mismatches fail explicitly instead of producing a partly usable menu. The local warning interval does not participate in the handshake.

The server sends session metadata first. The client attaches its menu, validates the policy and confirms readiness before the server emits state bodies. This adds one confirmation round trip at opening and removes the need for a separate early-data buffer with a hidden limit. Startup waiting uses `state.timeoutTicks`.

## Readiness and publication

The client stages fragments until a complete validated revision is available. `hasSnapshot()` means a usable committed snapshot exists and stays true while a later delta is assembling. `Status.SYNCING` alone does not require disabling the UI. Read `status()` and `statistics()` on the game thread; progress counts operations, not compressed wire bytes.

Sessions use fresh server-issued identifiers; reusing a container ID does not authorize an old session. ACKs advance the baseline and intermediate business states coalesce while an update is in flight. Protocol version **5** requires matching library artifacts and declarations. See [build and test](build-and-test.md) for validation and collection profiling.
