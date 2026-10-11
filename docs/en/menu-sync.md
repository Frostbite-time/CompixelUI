# Menu synchronization

[简体中文](../zh-CN/menu-sync.md) · [All guides](../README.md)

Menu sync keeps the state of a server-side menu on the client, and carries typed requests from the client back to the server. CompixelUI batches, splits and acknowledges the traffic; your menu keeps its own rules about who may change what.

```mermaid
sequenceDiagram
    participant S as Server menu
    participant C as Client menu
    S->>C: Full snapshot when the menu opens
    loop While the menu is open
        S->>C: Changed values, in batches
    end
    C->>S: Request, such as set.enabled = false
    S-->>C: Result: applied, rejected, …
```

Install CompixelUI on both the server and the client, and declare the dependency with `side = "BOTH"`. Menu sync has no UI code, so it runs on a dedicated server.

## Declare the state

Implement `SyncedMenu` on your menu and describe its fields in a `SyncSchema`:

```java
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

- On the server, call `captureMachine` whenever the machine changes. The new values reach every client viewing the menu.
- On the client, call `requestEnabled` from the game thread, for example in the `handle` of a `ComposeMenuScreen`. `true` means the request was queued, not that it succeeded.
- The action handler runs on the server. Check permissions there, then change the machine.

Imports are in `dev.compixel.sync.state` (schemas and codecs), `dev.compixel.sync.action` (requests and results), `dev.compixel.sync.session` (status) and `dev.compixel.forge.sync` (menu binding).

## Values

Getters must return immutable, non-null values. To change a value, replace it; don't mutate it in place.

| Codec | For |
| --- | --- |
| `SyncCodecs.INT`, `LONG`, `BOOLEAN`, `UUID` | Plain values |
| `SyncCodecs.STRING` | Strings |
| `SyncCodecs.enumeration(MyEnum.class)` | Enums with up to 256 constants |
| `SyncCodec.of(id, writer, reader)` | Your own types |
| `MinecraftSyncCodecs.registry(id, streamCodec)` | Minecraft values such as `ItemStack` |

```java
private static final SyncCodec<ItemStack> ITEM =
    MinecraftSyncCodecs.registry("example:item/1", ItemStack.OPTIONAL_STREAM_CODEC);
```

On Forge 1.20.1, use `MinecraftSyncCodecs.buffer(id, codec)` with a `FriendlyByteBuf` codec instead. Change a codec's ID or the schema version whenever its wire format changes.

## Collections

`keyedCollection` syncs a list whose entries have unique keys. For large collections with frequent small changes, use a `SyncMap` with `keyedMap`: only changed entries are sent.

```java
final class CatalogState {
    record Entry(int id, String name) {}
    private SyncMap<Integer, Entry> entries = SyncMap.empty();

    private static final SyncCodec<Entry> ENTRY = SyncCodec.of("example:entry/1",
        (out, entry) -> { out.writeInt(entry.id()); SyncCodecs.STRING.write(out, entry.name()); },
        in -> new Entry(in.readInt(), SyncCodecs.STRING.read(in)));

    static final SyncSchema<CatalogState> SCHEMA = SyncSchema.<CatalogState>builder("example:catalog", 1)
        .keyedMap("entries", SyncCodecs.INT, ENTRY, Entry::id, m -> m.entries, (m, v) -> m.entries = v)
        .build();

    void upsert(int id, String name) { entries = entries.with(id, new Entry(id, name)); }
    void remove(int id) { entries = entries.without(id); }
}
```

A map has no order; sort it where you display it. `current.forEachChange(previous, handler)` lists what changed between two versions. On the client, `sync.onUpdate(menu -> …)` runs once a complete update has arrived.

## Requests and results

`request(action, value)` returns an `ActionSubmission`. If it wasn't queued, `failure()` says why:

| Failure | Meaning |
| --- | --- |
| `BODY_BYTES`, `CODEC` | The value is too large or invalid for this action. |
| `NOT_READY`, `CLOSED`, `TRANSPORT_UNAVAILABLE`, `UNKNOWN_ACTION` | The menu or connection can't take requests now. |

`actual()` and `limit()` give the sizes involved. Nothing is retried automatically.

An action's value can be up to 8 KiB. To allow more, declare a maximum with `MenuAction.of(id, codec, maxBytes, handler)`: larger values are sent in parts, and the server refuses any value above the maximum.

Register `onActionResult(listener)` before the menu opens to hear back from the server. Each `ActionResult` has an `ActionStatus`: `APPLIED`, `REJECTED`, `INVALID` or `THROTTLED`, the last for requests beyond 16 that one player starts in one tick. The new state itself arrives through normal syncing. Rejections are also logged, at most once per 100 ticks per menu.

## When the state arrives

The server sends the state when the menu opens, then the values that change. A small state arrives together with the menu. A large one, such as a storage holding tens of thousands of different items, arrives in parts over the next ticks while the rest of the game's traffic keeps moving; a single value larger than a packet is split the same way. Each update takes effect all at once, so the UI never shows half of one.

`hasSnapshot()` becomes true once the first complete state has arrived, and stays true while later updates arrive. `status()` returns a `SyncStatus`, and `statistics()` reports progress.
