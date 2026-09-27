# menu-sync

The protocol core of Compose MC's menu synchronization: schemas, codecs, immutable `SyncMap` snapshots, bounded batching and typed client requests. It is plain Java 17 with no dependencies and no Minecraft code; the Minecraft adapters add menus and networking.

Compose MC 菜单同步的协议核心：数据结构、编解码器、不可变的 `SyncMap` 快照、有上限的分批传输和类型化的客户端请求。它是不含任何依赖和 Minecraft 代码的纯 Java 17 模块，菜单与网络由 Minecraft 适配器接入。

- [Menu synchronization guide](../docs/en/menu-sync.md) · [菜单同步指南](../docs/zh-CN/menu-sync.md)
