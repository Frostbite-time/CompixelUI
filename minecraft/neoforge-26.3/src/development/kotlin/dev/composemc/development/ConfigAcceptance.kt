package dev.composemc.development

import dev.composemc.neoforge.config.NeoForgeConfigEditor
import dev.composemc.neoforge.config.NeoForgeConfigEditor.Input
import dev.composemc.neoforge.config.NeoForgeConfigEditor.Result
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

/** A development-only client config that the acceptance suite edits through the library's config editor. */
internal object ConfigAcceptance {
    private const val FILE = "composemc-port-acceptance.toml"
    private val builder = ModConfigSpec.Builder()
    private val count = builder.defineInRange("count", 5, 0, 100)
    private val names = builder.defineList("names", listOf("sample"), { "" }) { it is String }
    private val spec = builder.build()

    fun register(container: ModContainer) = container.registerConfig(ModConfig.Type.CLIENT, spec, FILE)

    fun verify(): String {
        val editor = NeoForgeConfigEditor("composemc_development")
        val file = editor.snapshot().single { it.id() == FILE }
        check(file.access() == NeoForgeConfigEditor.Access.EDITABLE) { "Acceptance config is not editable: ${file.access()}" }
        val oldCount = count.get()
        val oldNames = names.get().toList()
        check(editor.stage(FILE, "count", Input.scalar("101")) == Result.INVALID) { "An out-of-range value was accepted" }
        check(editor.stage(FILE, "count", Input.scalar("42")) == Result.OK) { "A valid value was rejected" }
        check(count.get() == oldCount) { "Staging changed the live config" }
        check(editor.stage(FILE, "names", Input.list(listOf("alpha", "中文"))) == Result.OK) { "A valid list was rejected" }
        check(editor.save(FILE).result() == Result.OK) { "Saving the config failed" }
        check(count.get() == 42 && names.get() == listOf("alpha", "中文")) { "Saved values did not reach the live config" }
        check(editor.stage(FILE, "count", Input.scalar(oldCount.toString())) == Result.OK)
        check(editor.stage(FILE, "names", Input.list(oldNames)) == Result.OK)
        check(editor.save(FILE).result() == Result.OK) { "Restoring the config failed" }
        check(count.get() == oldCount && names.get() == oldNames) { "The config was not restored" }
        return FILE
    }
}
