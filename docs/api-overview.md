# Module Compose MC

## API reference / API 参考

This reference contains the public Kotlin and Java declarations for the Minecraft adapter named above and the shared Compose MC modules it distributes. The standard, `with-kotlin` and compile-only `dev` artifacts share this source and documentation set. Third-party Kotlin, Compose and Minecraft sources are provided by their respective upstream projects.

本参考包含上方 Minecraft 适配器及随它分发的共享 Compose MC 模块的公开 Kotlin/Java 声明。标准版、`with-kotlin` 和仅编译用 `dev` 共用这套源码与文档。第三方 Kotlin、Compose 和 Minecraft 源码由各自上游提供。

### Where to start / 从哪里开始

- `dev.composemc.ui.ore`: Ore controls, theme and interaction state / Ore 控件、主题与交互状态。
- `dev.composemc.host`: screen sessions and immutable state/action bindings / 屏幕会话及不可变状态与动作绑定。
- `dev.composemc.sync`: server-safe menu synchronization / 可在服务端使用的菜单同步。
- `dev.composemc.slots`: loader-independent slot policies and transfer routes / 与加载器无关的槽位策略与转移路径。
- `dev.composemc.forge`: the selected target's adapter, with the same names on Forge and NeoForge / 当前版本的适配器，Forge 与 NeoForge 使用相同名称。

Member descriptions come from the existing KDoc/Javadoc comments. Type signatures do not replace the documented thread, lifetime and synchronization requirements. Minecraft-facing sources use the readable development mappings, including Forge 1.20.1.

成员说明来自现有 KDoc/Javadoc 注释。类型签名不能替代线程、生命周期及同步约束说明。涉及 Minecraft 的源码采用可读开发映射，Forge 1.20.1 也如此。

See the [English guides](https://github.com/Frostbite-time/compose-mc/tree/main/docs/en) and [简体中文指南](https://github.com/Frostbite-time/compose-mc/tree/main/docs/zh-CN) for complete screen, inventory and synchronization examples. The source and documentation of Compose MC are licensed under MIT; third-party components retain their own licenses.

完整的屏幕、容器和同步示例见上面的中英文指南。Compose MC 的源码和文档采用 MIT，第三方组件保留各自许可。
