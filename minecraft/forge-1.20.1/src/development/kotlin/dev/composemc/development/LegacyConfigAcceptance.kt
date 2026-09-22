package dev.composemc.development

import dev.composemc.forge.config.ForgeConfigEditor
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig

internal object LegacyConfigAcceptance {
    private val builder = ForgeConfigSpec.Builder()
    private val count = builder.defineInRange("count", 5, 0, 100)
    private val names = builder.defineList("names", listOf("sample")) { it is String }
    private val spec = builder.build()
    fun register() { ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, spec, "composemc-port-acceptance.toml") }
    fun verify() {
        val editor = ForgeConfigEditor("composemc_development")
        val file = editor.snapshot().single { it.id() == "composemc-port-acceptance.toml" }
        check(file.access() == ForgeConfigEditor.Access.EDITABLE)
        val oldCount = count.get()
        val oldNames = names.get().map { it.toString() }
        check(editor.stage(file.id(), "count", ForgeConfigEditor.Input.scalar("101")) == ForgeConfigEditor.Result.INVALID)
        check(editor.stage(file.id(), "count", ForgeConfigEditor.Input.scalar("42")) == ForgeConfigEditor.Result.OK)
        check(count.get() == oldCount) { "Staging changed live config" }
        check(editor.stage(file.id(), "names", ForgeConfigEditor.Input.list(listOf("alpha", "中文"))) == ForgeConfigEditor.Result.OK)
        check(editor.save(file.id()).result() == ForgeConfigEditor.Result.OK)
        check(count.get() == 42 && names.get() == listOf("alpha", "中文"))
        check(editor.stage(file.id(), "count", ForgeConfigEditor.Input.scalar(oldCount.toString())) == ForgeConfigEditor.Result.OK)
        check(editor.stage(file.id(), "names", ForgeConfigEditor.Input.list(oldNames)) == ForgeConfigEditor.Result.OK)
        check(editor.save(file.id()).result() == ForgeConfigEditor.Result.OK)
    }
}
