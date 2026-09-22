# Menu synchronization / 菜单同步

`menu-sync` is the Minecraft-independent, dependency-free Java 17 protocol core. It provides schemas, bounded incremental updates, immutable `SyncMap` snapshots and complete-revision publication. A transport must supply reliable, ordered delivery; Minecraft adapters provide menu lifecycle and networking integration.

`menu-sync` 是独立于 Minecraft、无生产依赖的 Java 17 协议核心，提供声明式数据结构、有界增量更新、不可变 `SyncMap` 快照和完整版本发布。传输层需要保证可靠、有序交付；Minecraft 适配器负责菜单生命周期与网络集成。

`MenuSyncOptions` exposes state and action budgets. `TransferBudget` separates sustained refill, saved burst credit and peak bytes per tick. `ActionQueue` returns explicit admission outcomes and preserves FIFO ordering; adapters own connection lifetime, packet envelopes and diagnostic delivery.

`MenuSyncOptions` 公开状态与动作预算，`TransferBudget` 分别设置持续补充、突发积蓄和每 tick 峰值。`ActionQueue` 返回明确的入队结果并保持 FIFO 顺序；连接生命周期、物理包长和诊断结果传递由适配器负责。

- [English: API and usage](../docs/en/menu-sync.md) · [中文：API 与用法](../docs/zh-CN/menu-sync.md)
- [English: build and profiling](../docs/en/build-and-test.md) · [中文：构建与性能测量](../docs/zh-CN/build-and-test.md)
