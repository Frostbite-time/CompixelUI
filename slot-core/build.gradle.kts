plugins {
    `java-library`
    id("composemc.testing")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

val verifyServerCore by tasks.registering {
    group = "verification"
    doLast {
        check(configurations.getByName("compileClasspath").files.isEmpty())
        check(configurations.getByName("runtimeClasspath").files.isEmpty())
        logger.lifecycle("Slot core PASS: Java 17 bytecode, no production dependencies")
    }
}

tasks.check { dependsOn(verifyServerCore) }

rootProject.tasks.named("verifyCoreBoundary") { dependsOn(verifyServerCore) }
