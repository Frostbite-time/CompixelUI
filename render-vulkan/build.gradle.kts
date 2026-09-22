plugins { kotlin("jvm") }
val lwjglVersion = libs.versions.lwjgl.vulkan.get()
dependencies {
    api(project(":render"))
    compileOnly(libs.skiko.vulkan)
    compileOnly("org.lwjgl:lwjgl:$lwjglVersion")
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
}
