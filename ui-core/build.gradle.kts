plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(compose.runtime)
    // Colors of schemes and their values
    api(compose.ui)
}
