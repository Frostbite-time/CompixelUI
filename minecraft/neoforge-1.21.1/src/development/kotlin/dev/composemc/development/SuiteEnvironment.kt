package dev.composemc.development

import dev.composemc.testing.suite.ClientSuite

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** Launch settings of the automated client suites, read once from system properties. */
internal object SuiteEnvironment {
    val suite: ClientSuite? = System.getProperty("composemc.suite")?.let(ClientSuite::of)
    /** Hidden runs hide their own window after startup; the Windows launcher also isolates its desktop. */
    val background = suite != null && java.lang.Boolean.getBoolean("composemc.suite.background")
    /** Set only by launchers that install the release archives into a real loader profile. */
    val production = java.lang.Boolean.getBoolean("composemc.suite.production")
    /** A benchmark control run performs the same startup, world and reload without any Compose renderer. */
    val control = suite == ClientSuite.BENCHMARK && java.lang.Boolean.getBoolean("composemc.benchmark.control")

    /** Logical window focus of suite-driven screens. Only the acceptance FOCUS step changes it. */
    @Volatile var focused = true

    /** Suites never read OS focus, so hidden and visible runs execute identical frames. */
    fun uiFocused(osFocused: Boolean): Boolean = if (suite != null) focused else osFocused
}
