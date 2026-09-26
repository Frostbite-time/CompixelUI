// Protocol code deliberately uses only java.*; servers never need the UI runtime to execute it.
plugins {
    `java-library`
    id("composemc.testing")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

val verifyServerCore by tasks.registering {
    group = "verification"
    doLast {
        check(configurations.getByName("compileClasspath").files.isEmpty()) {
            "Menu sync core gained a compile dependency"
        }
        check(configurations.getByName("runtimeClasspath").files.isEmpty()) {
            "Menu sync core gained a runtime dependency"
        }
        logger.lifecycle("Server core PASS: Java 17 bytecode, no production runtime dependencies")
    }
}

tasks.check { dependsOn(verifyServerCore) }

rootProject.tasks.named("verifyCoreBoundary") { dependsOn(verifyServerCore) }

for (variant in listOf("Collection", "Map")) {
    tasks.register<JavaExec>("profileSync$variant") {
        group = "verification"
        description = "Measure immutable $variant updates in an isolated JVM; not a CI timing gate."
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.composemc.sync.SyncMapPerformance")
        jvmArgs("-Xms256m", "-Xmx512m")
        args(
            variant.lowercase(),
            layout.buildDirectory.file("profiles/${variant.lowercase()}.json").get().asFile.absolutePath,
        )
    }
}
