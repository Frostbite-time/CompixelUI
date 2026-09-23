plugins {
    kotlin("jvm")
    `java-test-fixtures`
}
val lwjglVersion = libs.versions.lwjgl.gl.get()
dependencies {
    api(project(":render"))
    // Minecraft owns LWJGL and its natives; do not bundle a second copy into the runtime.
    compileOnly("org.lwjgl:lwjgl:$lwjglVersion")
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    // Real-context probes run inside Minecraft, which supplies the native libraries.
    testFixturesCompileOnly("org.lwjgl:lwjgl:$lwjglVersion")
    testFixturesCompileOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    testFixturesApi(project(":testing"))
}
