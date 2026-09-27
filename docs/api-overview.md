# Module Compose MC

## API reference / API 参考

This reference lists the public Kotlin and Java API of Compose MC for the Minecraft version named above, including the shared modules it ships with. Guides with complete examples are in the [English documentation](https://github.com/Frostbite-time/compose-mc/tree/main/docs/en).

本参考列出上方所示 Minecraft 版本的 Compose MC 公开 Kotlin 与 Java API，包括随之发布的共享模块。含完整示例的指南见[中文文档](https://github.com/Frostbite-time/compose-mc/tree/main/docs/zh-CN)。

### Where to start / 从哪里开始

- `dev.composemc.forge`: screens, container screens and HUD layers; `item`, `slots`, `sync` and `config` hold items and tooltips, menu slots, menu sync and config screens. / 界面、容器界面与 HUD 层；`item`、`slots`、`sync`、`config` 分别对应物品与提示、菜单槽位、菜单同步和配置界面。
- `dev.composemc.ui.ore`: Ore UI components and theme. / Ore UI 组件与主题。
- `dev.composemc.host`: `UiBinding` and UI sessions. / `UiBinding` 与界面会话。
- `dev.composemc.sync`: the menu synchronization protocol, which also runs on servers. / 菜单同步协议，也可在服务端运行。
- `dev.composemc.slots`: slot rules and shift-click routes. / 槽位规则与 Shift 点击路线。

Compose MC is licensed under MIT; bundled libraries keep their own licenses. / Compose MC 采用 MIT 许可，随附的库保留各自的许可。
