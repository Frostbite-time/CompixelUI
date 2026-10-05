plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":compose-bridge"))
    api(project(":ui-core"))
    api(compose.animation)
    testImplementation(compose.foundation)
    testRuntimeOnly(compose.desktop.currentOs)
}
