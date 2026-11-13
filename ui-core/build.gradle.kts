plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":platform"))
    api(compose.runtime)
    // Colors of schemes and their values
    api(compose.ui)
}
