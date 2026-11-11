plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":platform"))
    api(project(":render"))
    api(compose.runtime)
    api(compose.ui)
    implementation(compose.foundation)
    // Skiko's AWT classes load it; CompixelUI itself dispatches on its host's thread.
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
    implementation("androidx.navigationevent:navigationevent:1.1.1")
    testRuntimeOnly(compose.desktop.currentOs)
}
