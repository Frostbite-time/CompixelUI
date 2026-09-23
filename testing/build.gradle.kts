plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":demo"))
    implementation(compose.foundation)
    testRuntimeOnly(compose.desktop.currentOs)
}
