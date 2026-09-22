plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
dependencies {
    api(project(":compose-bridge"))
    testImplementation(compose.foundation)
    testRuntimeOnly(compose.desktop.currentOs)
}
