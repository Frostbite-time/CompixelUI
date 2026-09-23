plugins {
    kotlin("jvm")
    `java-test-fixtures`
}
val lwjglVersion = libs.versions.lwjgl.vulkan.get()
dependencies {
    api(project(":render"))
    compileOnly(libs.skiko.vulkan)
    compileOnly("org.lwjgl:lwjgl:$lwjglVersion")
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    testFixturesApi(project(":testing"))
    testFixturesCompileOnly(libs.skiko.vulkan)
    testFixturesCompileOnly("org.lwjgl:lwjgl:$lwjglVersion")
    testFixturesCompileOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
}
