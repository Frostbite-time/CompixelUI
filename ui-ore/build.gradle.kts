plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":ui-core"))
    api(compose.foundation)
}
