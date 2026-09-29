import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    application
}

dependencies {
    implementation(project(":demo"))
    implementation(project(":testing"))
    implementation(compose.desktop.currentOs)
}

application { mainClass.set("dev.compixel.desktop.MainKt") }

// JetBrains relay artifacts and AndroidX implementations may have identical filenames.
// Preserve both and use the same qualified names in the distribution and launch scripts.
val distributionLibraryNames = providers.provider {
    configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.associate {
        it.file to "${it.moduleVersion.id.group}.${it.file.name}"
    }
}
val distributionLibraries =
    tasks.register<Sync>("prepareDistributionLibraries") {
        from(configurations.runtimeClasspath)
        into(layout.buildDirectory.dir("distribution-libraries"))
        eachFile { distributionLibraryNames.get()[file]?.let { name = it } }
    }

distributions.main {
    contents {
        eachFile {
            distributionLibraryNames.get()[file]?.let { name = it }
        }
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(distributionLibraries)
    classpath =
        files(
            tasks.jar,
            distributionLibraryNames.map { names ->
                names.values.map { layout.buildDirectory.file("distribution-libraries/$it").get().asFile }
            },
        )
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Render real Compose fixtures offscreen and verify pixels."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("--smoke", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
