# Configuration screens

[简体中文](../zh-CN/configuration.md) · [Documentation](../README.md)

Compose MC supplies an Ore editor for registered loader configuration specs. It includes file tabs, search, validation, defaults, undo/refresh, per-file saving and unsaved-change dismissal. Consumers keep their normal specs and persistence hooks.

## Register a screen

On NeoForge, register from your **client entry point**, using its `ModContainer`:

```java
import dev.composemc.neoforge.NeoForgeComposeConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

final class ConfigRegistration {
    static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
            NeoForgeComposeConfigScreen::new);
    }
}
```

Forge 1.20.1 provides `dev.composemc.forge.ForgeComposeConfigScreen`. Capture the consumer's `ModContainer` during construction, then register its `ConfigScreenHandler.ConfigScreenFactory` from the client entry point with a factory that constructs `new ForgeComposeConfigScreen(modContainer, parent)`. Forge and NeoForge extension-point registration signatures differ; compile against the selected target.

## Editing and saving

Supported values include Boolean, signed 32/64-bit integers, finite doubles, strings, enums and flat homogeneous scalar lists. The editor follows the specification's allowed values and list constraints. Unknown/custom types remain read-only. Forge 1.20.1 infers homogeneous list types from nonempty defaults/current values because its public spec API lacks the modern element specification.

Edits are buffered locally. **Use value** validates and stages an edit; **Save this file** checks access, current values and constraints again before applying and saving through the loader. Unrelated external changes are retained. Conflicts require an explicit refresh. Closing with drafts asks whether to discard them.

Defaults stage a value; they do not immediately modify the file. Saved restart requirements are displayed without automatically restarting the game. Available restart categories follow the selected loader version; Forge 1.20.1 does not expose NeoForge's STARTUP/GAME settings.

Scalar inputs are bounded to 1,048,576 UTF-16 units and lists to 100,000 entries. Larger/unsupported data is not offered for editing. These are UI bounds, not changes to the consumer's configuration format.

## File and world ownership

The editor follows the file selected by the loader. Unloaded or non-file-backed configs cannot be saved. Remote SERVER configs are read-only, as are integrated-server configs while the world is published to LAN.

A SERVER file may be shared under `config/` or overridden per world, depending on loader behavior. Drafts are bound to the active world/config/path context so they cannot carry into another world. A same-context reload retains drafts only while their original values remain unchanged.

Save failure attempts to restore values changed by the editor. It cannot roll back arbitrary mod reload-event side effects or concurrent third-party writes; inspect the error and refresh before continuing.

## Custom presentation

`dev.composemc.neoforge.config.NeoForgeConfigEditor` is a public client-game-thread model. It exposes immutable file/entry snapshots, so a custom Compose page need not read live loader specs. Use the IDs supplied by the snapshot, not display labels.

```java
import dev.composemc.neoforge.config.NeoForgeConfigEditor;

final class ConfigEdits {
    static NeoForgeConfigEditor.Result stageLimit(
            NeoForgeConfigEditor editor, String fileId, String entryId, String text) {
        return editor.stage(fileId, entryId, NeoForgeConfigEditor.Input.scalar(text));
    }
}
```

Construct the editor with your mod ID. Call `snapshot()` for files, `Input.list(values)` for lists, `reset` for a staged default, `reload` to discard a file's drafts and recapture, and `discardAll` to drop all drafts. Call `save(fileId)` in response to a Save action and inspect `SaveResult` for conflicts, errors and restart requirements. Forge exposes the equivalent `ForgeConfigEditor`.

Entry IDs escape `~` and `/` in path segments, preserving literal dotted keys. Label lookup prefers the spec translation key, then `<modid>.configuration.<dot-joined-path>`. A matching `.tooltip` key overrides the spec comment. Enum labels try `<entry-key>.<constant-lowercase>`, then `<modid>.configuration.option.<constant-lowercase>`, then a readable fallback.

Use [UiBinding](getting-started.md) for actions between your custom composition and the editor. Keep permission, file and world checks in the game-thread model.
