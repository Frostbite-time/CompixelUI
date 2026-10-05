import java.util.Properties
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://repo.polyfrost.org/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

// Settings plugins resolve before the project's version catalog. Supply missing
// compiler toolchains even when an IDE launches Gradle with a different JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven("https://repo.polyfrost.org/releases")
        mavenCentral()
        google()
    }
}

rootProject.name = "CompixelUI"

include("platform", "render", "render-gl", "render-vulkan", "compose-bridge", "host", "demo", "desktop", "testing")

include("ui-core", "ui-ore", "menu-sync", "slot-core", "runtime-standard", "runtime-vulkan", "runtime-kotlin")

val targets = Properties().apply { file("gradle/minecraft-targets.properties").reader().use { load(it) } }
val supported = targets.getProperty("targets").split(",")
val selection = providers.gradleProperty("compixelTargets").orElse("all").get()
val enabled =
    when (selection) {
        "all" -> supported
        "none" -> emptyList()
        else ->
            selection.split(",").map(String::trim).distinct().also {
                require(it.all(supported::contains)) { "Unknown compixelTargets=$selection; supported: $supported" }
            }
    }

enabled.forEach { mc ->
    val directory = requireNotNull(targets.getProperty("$mc.project")) { "Missing project directory for $mc" }
    val projectPath = ":" + directory.replace('/', ':')
    include(projectPath)
    project(projectPath).projectDir = file(directory)
}

project(":runtime-standard").projectDir = file("runtimes/standard")

project(":runtime-vulkan").projectDir = file("runtimes/vulkan")

project(":runtime-kotlin").projectDir = file("runtimes/kotlin")
