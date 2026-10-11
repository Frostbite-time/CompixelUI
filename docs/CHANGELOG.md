# CompixelUI 0.1.10-alpha.16

- Breaking: menu sync has no settings anymore. `MenuSyncOptions`, `SyncLimits`, `ActionLimits` and `TransferBudget` are gone, and `MenuSync.bind(menu, schema)` is the only way to bind a menu. `SyncCodecs.string(maxBytes)` is now `SyncCodecs.STRING`, and `MinecraftSyncCodecs.registry` (`buffer` on Forge 1.20.1) no longer takes a size. An action still declares its largest value with `MenuAction.of(id, codec, maxBytes, handler)`. See [Menu synchronization](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/menu-sync.md).
- Changed: values of any size synchronize. A state or a single value larger than any Minecraft packet arrives in batches and takes effect all at once, and nothing in menu sync times out.
- Changed: menu sync paces a menu's state by what the client has received: at most 512 KiB that the client has not acknowledged is on its way, with no rate budget. Large states reach fast connections much sooner, and on slow connections other game traffic keeps moving. An action sent in parts waits in the same way for the server to confirm what arrived.
- Changed: a menu's state follows its opening at once instead of after a reply from the client, so the menu has its data one round trip sooner.
- Removed: the `ActionFailure` values `QUEUE_BYTES`, `PENDING_ACTIONS`, `QUEUE_TIMEOUT`, `SEND_TIMEOUT`, `REPLY_TIMEOUT` and `ASSEMBLY_TIMEOUT`, and `ActionStatus.EXPIRED`.
- Changed: the menu sync protocol version is now 6, so the client and the server need this version of CompixelUI.
