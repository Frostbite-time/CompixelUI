plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":host"))
    api(project(":ui-ore"))
    implementation(compose.foundation)
}
