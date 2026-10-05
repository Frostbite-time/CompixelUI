# Module CompixelUI

## API reference / API 参考

This reference lists the public Kotlin and Java API of CompixelUI for the Minecraft version named above, including the shared modules it ships with. Guides with complete examples are in the [English documentation](https://github.com/Frostbite-time/CompixelUI/tree/main/docs/en).

本参考列出上方所示 Minecraft 版本的 CompixelUI 公开 Kotlin 与 Java API，包括随之发布的共享模块。含完整示例的指南见[中文文档](https://github.com/Frostbite-time/CompixelUI/tree/main/docs/zh-CN)。

### Where to start / 从哪里开始

- `dev.compixel.forge`: screens, container screens and HUD layers, which show game state through `snapshot`, `handle` and `Content`; `item`, `slots` and `sync` hold items and tooltips, menu slots and menu sync. / 界面、容器界面与 HUD 层，通过 `snapshot`、`handle` 和 `Content` 显示游戏状态；`item`、`slots`、`sync` 分别对应物品与提示、菜单槽位和菜单同步。
- `dev.compixel.ui.ore`: Ore UI components and theme. / Ore UI 组件与主题。
- `dev.compixel.host`: UI sessions, `UiBinding` and the `UiStateBinding` that state screens use. / 界面会话、`UiBinding`，以及状态界面使用的 `UiStateBinding`。
- `dev.compixel.sync`: the menu synchronization protocol, which also runs on servers. / 菜单同步协议，也可在服务端运行。
- `dev.compixel.slots`: slot rules and shift-click routes. / 槽位规则与 Shift 点击路线。

CompixelUI is licensed under MIT; bundled libraries keep their own licenses. / CompixelUI 采用 MIT 许可，随附的库保留各自的许可。
