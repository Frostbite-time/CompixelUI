# CompixelUI 0.1.10-alpha.5

- Breaking: CompixelUI no longer ships a config screen. `ComposeConfigScreen`, `ConfigEditor` and their translations are removed, because editing config files is not a UI framework's job. On NeoForge, register NeoForge's own screen from your client mod constructor with `container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new)`. Forge 1.20.1 has no built-in config screen: use a config library's screen, or let players edit the config files.
- Breaking: removed unused API. `UiSession.setActive`, `SessionState.SUSPENDED`, `UiSession.diagnosticThread()` and `UiDesign.None` are gone, and a `UiLayerSurface` always takes its item, tooltip and drawing image sources.
