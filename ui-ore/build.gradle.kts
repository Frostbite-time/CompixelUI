import dev.compixel.gradle.XzResources

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api(project(":ui-core"))
    api(compose.foundation)
    // Unpacks the Compixel font, which ships xz-compressed.
    implementation(libs.xz)
}

// Files under src/main/xzResources ship as resources named <file>.xz.
val xzResources =
    tasks.register("xzResources") {
        val source = layout.projectDirectory.dir("src/main/xzResources")
        val output = layout.buildDirectory.dir("generated/xz-resources")
        inputs.dir(source)
        outputs.dir(output)
        doLast { XzResources.compress(source.asFile, output.get().asFile) }
    }

sourceSets.main { resources.srcDir(xzResources) }
