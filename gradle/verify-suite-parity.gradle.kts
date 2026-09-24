import java.util.Properties

// Each adapter owns its copy of the client suite drivers, but the copies must stay byte-identical:
// every Minecraft version then runs the same acceptance steps and the same benchmark protocol.
// Version differences belong in each adapter's SuitePlatform.kt and its version-specific fixtures.
val suiteTargets = Properties().apply { rootProject.file("gradle/minecraft-targets.properties").reader().use { load(it) } }
val suiteDirectories: Map<String, File> = suiteTargets.getProperty("targets").split(",").associateWith { target ->
    rootProject.file(requireNotNull(suiteTargets.getProperty("$target.project")) { "Missing project directory for $target" } +
        "/src/development/kotlin/dev/composemc/development")
}
val identicalSuiteFiles = listOf("SuiteEnvironment.kt", "SuiteSession.kt", "ClientAcceptanceProbe.kt", "PreviewAcceptance.kt",
    "ClientBenchmarkProbe.kt", "NativeTooltipProbe.kt")
val adapterSuiteFiles = listOf("SuitePlatform.kt", "DevelopmentClientBootstrap.kt", "InventoryAcceptanceProbe.kt",
    "MenuSyncAcceptanceProbe.kt", "ConfigAcceptance.kt", "BenchmarkScreen.kt", "ComposePreviewScreen.kt",
    "render/PortValidationScreen.kt", "render/NativeItemVisualScreen.kt", "render/RendererChecks.kt")

tasks.register("verifySuiteParity") {
    group = "verification"
    description = "Requires every Minecraft adapter to carry identical client suite drivers and its own platform bindings."
    val directories = suiteDirectories
    val identical = identicalSuiteFiles
    val adapterFiles = adapterSuiteFiles
    val root = rootDir
    inputs.files(directories.values.flatMap { directory -> (identical + adapterFiles).map { File(directory, it) } }.filter { it.isFile })
    doLast {
        val problems = mutableListOf<String>()
        fun path(file: File) = file.relativeTo(root).invariantSeparatorsPath
        for ((target, directory) in directories) {
            for (name in identical + adapterFiles) {
                val file = File(directory, name)
                if (!file.isFile) problems += "${path(file)} is missing; $target would not run the full client suites"
            }
            val platform = File(directory, "SuitePlatform.kt")
            if (platform.isFile && "const val MINECRAFT = \"$target\"" !in platform.readText())
                problems += "${path(platform)} must declare const val MINECRAFT = \"$target\""
        }
        for (name in identical) {
            val variants = directories.mapNotNull { (target, directory) -> File(directory, name).takeIf { it.isFile }?.let { target to it.readText() } }
                .groupBy({ it.second }, { it.first })
            if (variants.size > 1) problems += "$name differs between adapters: " + variants.values.joinToString(" | ") { it.joinToString(", ") }
        }
        check(problems.isEmpty()) { "Client suite parity violated:\n" + problems.joinToString("\n") }
    }
}
