# 配置界面

[English](../en/configuration.md) · [文档目录](../README.md)

Compose MC 为已注册的加载器配置规范提供 Ore 编辑器，包含文件标签、搜索、校验、默认值、撤销/刷新、逐文件保存和未保存退出确认。消费者继续使用原来的配置声明及持久化钩子。

## 注册屏幕

在 NeoForge 的**客户端入口**中，使用消费者的 `ModContainer` 注册：

```java
import dev.composemc.forge.config.ComposeConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

final class ConfigRegistration {
    static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
            ComposeConfigScreen::new);
    }
}
```

Forge 1.20.1 使用同一个 `ComposeConfigScreen`，只是注册方式不同：构造模组时捕获消费者的 `ModContainer`，在客户端入口注册 `ConfigScreenHandler.ConfigScreenFactory`，其工厂创建 `new ComposeConfigScreen(modContainer, parent)`。Forge 与 NeoForge 的扩展点注册签名不同，应针对所选目标编译。

## 编辑与保存

支持 Boolean、有符号 32/64 位整数、有限 double、字符串、枚举，以及由同一种标量类型组成的平面列表。编辑器遵守配置规范的允许值及列表约束，未知/自定义类型只读。Forge 1.20.1 的公开 API 缺少现代列表元素规范，因此从非空默认值或当前值推断同质列表类型。

编辑内容先缓存在本地。**使用此值**校验并暂存修改，**保存此文件**再次检查访问条件、当前值和约束，再通过加载器应用和保存。无关的外部修改会保留，冲突需要显式刷新。带草稿关闭时会询问是否丢弃。

恢复默认值只会暂存，不会立即修改文件。保存后显示重启要求，不会自动重启游戏。可用的重启类别取决于加载器版本；Forge 1.20.1 不提供 NeoForge 的 STARTUP/GAME 设置。

单个标量输入上限为 1,048,576 个 UTF-16 单位，列表上限为 100,000 项。超限或不支持的数据不提供编辑。这些是 UI 限制，不改变消费者的配置文件格式。

## 文件与世界归属

编辑器遵循加载器实际选择的文件。未加载或不对应文件的配置不能保存。远程 SERVER 配置只读；单人世界开放至局域网时，其集成服务器配置也只读。

根据加载器行为，SERVER 文件可能位于共享 `config/`，也可能由世界内文件覆盖。草稿绑定当前世界、配置和路径，避免带入另一个世界。同一上下文发生重载时，只有原值未变化的草稿才会保留。

保存失败时尝试恢复编辑器修改的值，但无法回滚任意模组重载事件副作用或第三方并发写入。应检查错误并刷新后再继续。

## 自定义展示

`dev.composemc.forge.config.ConfigEditor` 是公开的客户端游戏线程模型，提供不可变文件/条目快照，自定义 Compose 页面不需要读取实时加载器规范。使用快照提供的 ID，不要使用显示标签作为标识。

```java
import dev.composemc.forge.config.ConfigEditor;

final class ConfigEdits {
    static ConfigEditor.Result stageLimit(
            ConfigEditor editor, String fileId, String entryId, String text) {
        return editor.stage(fileId, entryId, ConfigEditor.Input.scalar(text));
    }
}
```

使用模组 ID 构造编辑器。`snapshot()` 获取文件，`Input.list(values)` 创建列表输入，`reset` 暂存默认值，`reload` 丢弃单个文件草稿并重新捕获，`discardAll` 丢弃全部草稿。用户执行保存时调用 `save(fileId)`，并检查 `SaveResult` 中的冲突、错误和重启要求。Forge 提供对应的 `ConfigEditor`。

条目 ID 对路径段中的 `~` 和 `/` 转义，以区分包含点号的字面键。标签优先使用规范的翻译键，然后使用 `<modid>.configuration.<dot-joined-path>`；对应的 `.tooltip` 键可覆盖规范注释。枚举标签依次尝试 `<entry-key>.<constant-lowercase>`、`<modid>.configuration.option.<constant-lowercase>`，最后使用可读回退名。

自定义组合与编辑器之间可使用 [UiBinding](getting-started.md) 传递动作。权限、文件和世界校验应留在游戏线程模型中。
