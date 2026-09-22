import org.gradle.api.artifacts.ProjectDependency

// One shared dependency graph serves every enabled Minecraft target on the mainline.
val sharedDependencies = mapOf(
    ":platform" to emptySet(),
    ":menu-sync" to emptySet(),
    ":slot-core" to emptySet(),
    ":render" to setOf(":platform"),
    ":compose-bridge" to setOf(":platform", ":render"),
    ":host" to setOf(":compose-bridge"),
    ":render-gl" to setOf(":render"),
    ":render-vulkan" to setOf(":render"),
    ":ui-ore" to emptySet(),
    ":demo" to setOf(":host", ":ui-ore"),
    ":desktop" to setOf(":demo"),
)
val sharedProjects = sharedDependencies.keys.map(rootProject::project)
val forbiddenSource = Regex(
    """\b(?:net\s*\.\s*(?:minecraft(?:forge)?|neoforged|fabricmc)|com\s*\.\s*mojang|org\s*\.\s*spongepowered\s*\.\s*asm|dev\s*\.\s*composemc\s*\.\s*(?:neoforge|forge|minecraft))\b""",
)
val lwjglSource = Regex("""\borg\s*\.\s*lwjgl\b""")
val clientRuntimeSource = Regex("""\b(?:androidx\s*\.\s*compose|org\s*\.\s*jetbrains\s*\.\s*(?:skia|skiko)|java\s*\.\s*awt|javax\s*\.\s*swing)\b""")
val forbiddenGroups = listOf("net.minecraft", "net.minecraftforge", "net.neoforged", "net.fabricmc", "com.mojang", "org.spongepowered")

fun forbiddenGroup(group: String, projectPath: String): Boolean =
    forbiddenGroups.any { group == it || group.startsWith("$it.") } ||
        (projectPath !in setOf(":render-gl", ":render-vulkan") && (group == "org.lwjgl" || group.startsWith("org.lwjgl.")))

// Resolve each classpath from a task owned by its project, as required by Gradle 9.
val sharedChecks = sharedProjects.map { shared ->
    shared.tasks.register("verifyCoreBoundary") {
        group = "verification"
        inputs.files(shared.fileTree("src") { include("**/*.kt", "**/*.java") })
        doLast {
            val problems = mutableSetOf<String>()
            for (source in shared.fileTree("src") { include("**/*.kt", "**/*.java") }.sortedBy { it.path }) {
                source.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (forbiddenSource.containsMatchIn(line) ||
                            (shared.path !in setOf(":render-gl", ":render-vulkan") && lwjglSource.containsMatchIn(line)) ||
                            (shared.path in setOf(":menu-sync", ":slot-core") && clientRuntimeSource.containsMatchIn(line))) {
                            problems += "${source.relativeTo(rootDir).invariantSeparatorsPath}:${index + 1}: platform-specific reference in shared source"
                        }
                    }
                }
            }
            for (dependency in shared.configurations.flatMap { it.dependencies }.distinct()) {
                if (dependency is ProjectDependency) {
                    // java-test-fixtures adds a dependency on its own project's main variant.
                    if (dependency.path != shared.path && dependency.path !in sharedDependencies.getValue(shared.path)) {
                        problems += "${shared.path} must not depend on ${dependency.path}"
                    }
                } else if (dependency.group?.let { forbiddenGroup(it, shared.path) } == true) {
                    problems += "${shared.path} declares platform-specific dependency ${dependency.group}:${dependency.name}"
                }
            }
            // Stop before resolving an accidentally declared adapter dependency and its game artifacts.
            check(problems.isEmpty()) { "Shared core boundary violated:\n" + problems.joinToString("\n") }
            for (name in listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath",
                "testFixturesCompileClasspath", "testFixturesRuntimeClasspath")) {
                val configuration = shared.configurations.findByName(name) ?: continue
                for (component in configuration.incoming.resolutionResult.allComponents) {
                    val id = component.moduleVersion ?: continue
                    if (forbiddenGroup(id.group, shared.path)) {
                        problems += "${shared.path}:$name resolves platform-specific dependency $id"
                    }
                }
            }
            check(problems.isEmpty()) { "Shared core dependency boundary violated:\n" + problems.joinToString("\n") }
        }
    }
}

val verifyCoreBoundary = tasks.register("verifyCoreBoundary") {
    group = "verification"
    description = "Rejects Minecraft/loader coupling and reverse dependencies in shared core, graphics and fixtures."
    dependsOn(sharedChecks)
    doLast {
        logger.lifecycle("Core boundary PASS: ${sharedProjects.size} shared modules; LWJGL confined to render-gl and render-vulkan")
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyCoreBoundary) }
}
