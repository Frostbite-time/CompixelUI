import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    id("composemc.jvm-library") apply false
    id("composemc.skiko-profile") apply false
    id("composemc.runtime-bundle") apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.moddev) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.spotless)
}

abstract class ApiDocumentationWorkers : BuildService<BuildServiceParameters.None>

val apiDocumentationWorkers =
    gradle.sharedServices.registerIfAbsent("apiDocumentationWorkers", ApiDocumentationWorkers::class) {
        maxParallelUsages.set(1)
    }

allprojects {
    group = "dev.composemc"
    version = providers.gradleProperty("mod_version").get()
}

subprojects {
    tasks
        .matching { it.name.startsWith("dokkaGeneratePublication") }
        .configureEach {
            usesService(apiDocumentationWorkers)
        }
    plugins.withId("java") {
        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) { into("META-INF/composemc") }
        }
    }
    // Every publication shares these repositories and project metadata. Publishing to the Wintercogs Maven
    // needs the wintercogsUsername/wintercogsPassword properties of a Reposilite access token, which only the
    // release workflow supplies; see docs/en/build-and-test.md.
    plugins.withId("maven-publish") {
        val projectUrl = providers.gradleProperty("mod_url").get()
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "consumer"
                    setUrl(rootProject.layout.buildDirectory.dir("consumer-maven"))
                }
                maven {
                    name = "wintercogs"
                    url = uri("https://maven.wintercogs.com/releases")
                    credentials(PasswordCredentials::class)
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url = projectUrl
                    scm {
                        url = projectUrl
                        connection = "scm:git:$projectUrl.git"
                        developerConnection = "scm:git:ssh://git@github.com/Frostbite-time/compose-mc.git"
                    }
                    developers {
                        developer {
                            id = "Frostbite-time"
                            name = "Frostbite-time"
                        }
                    }
                }
            }
        }
    }
}

// Runtime bundles carry third-party code only and keep their own versions. The Kotlin bundle resolves
// the standard graph; verifyRuntimeArchive checks that both profiles expect the same Kotlin libraries.
val runtimeProfiles =
    mapOf(
        "standard" to mapOf("project" to ":runtime-standard", "skiko" to libs.versions.skiko.standard.get()),
        "vulkan" to mapOf("project" to ":runtime-vulkan", "skiko" to libs.versions.skiko.vulkan.get()),
        "kotlin" to mapOf("project" to ":runtime-kotlin", "skiko" to libs.versions.skiko.standard.get()),
    )
val targetSettings =
    java.util.Properties().apply {
        rootProject.file("gradle/minecraft-targets.properties").reader().use { load(it) }
    }

fun targetProject(mc: String) = ":" + targetSettings.getProperty("$mc.project").replace('/', ':')

val targetVersions = targetSettings.getProperty("targets").split(",").filter { findProject(targetProject(it)) != null }

extra["runtimeProfiles"] = runtimeProfiles

runtimeProfiles.forEach { (name, profile) ->
    val runtime = project(profile.getValue("project"))
    runtime.version = providers.gradleProperty("runtime_${name}_version").get()
    runtime.extensions.extraProperties.apply {
        set("runtime_profile", name)
        set("skiko_version", profile.getValue("skiko"))
    }
}

project(":render-vulkan").extra["skiko_version"] = libs.versions.skiko.vulkan.get()

subprojects {
    // Minecraft adapters and pure Java core libraries configure their plugins locally.
    if (!path.startsWith(":minecraft") && name !in setOf("menu-sync", "slot-core")) {
        apply(plugin = "composemc.jvm-library")
        if (extensions.extraProperties.has("skiko_version")) apply(plugin = "composemc.skiko-profile")
    }
}

apply(from = "gradle/verify-core-boundary.gradle.kts")

apply(from = "gradle/verify-suite-parity.gradle.kts")

// One formatter setup for the repository: `spotlessApply` rewrites. Only `checkCore` runs `spotlessCheck`;
// `check` and `build` skip it because formatting the whole repository is slow.
spotless {
    isEnforceCheck = false
    val outputs = listOf("**/build/**", "**/run/**", ".work/**")
    kotlin {
        target("**/src/**/*.kt")
        targetExclude(outputs)
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude(outputs)
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    java {
        target("**/src/**/*.java")
        targetExclude(outputs)
        palantirJavaFormat(libs.versions.palantir.java.format.get())
    }
    // Groovy build scripts only get whitespace rules.
    format("groovyGradle") {
        target("*.gradle", "**/*.gradle")
        targetExclude(outputs)
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks.register("checkCore") {
    group = "verification"
    dependsOn("verifyCoreBoundary", "verifySuiteParity", "spotlessCheck", ":desktop:smoke")
    dependsOn(
        listOf(
                "platform",
                "render",
                "render-gl",
                "render-vulkan",
                "compose-bridge",
                "host",
                "ui-ore",
                "menu-sync",
                "slot-core",
                "demo",
                "desktop",
                "testing",
            )
            .map { ":$it:check" }
    )
}

tasks.register("buildAllMods") {
    group = "build"
    description = "Build and verify all enabled Minecraft targets and their API artifacts."
    doFirst { check(targetVersions.isNotEmpty()) { "Enable Minecraft targets with -PcomposemcTargets=all" } }
    dependsOn(targetVersions.map { "${targetProject(it)}:build" })
}
