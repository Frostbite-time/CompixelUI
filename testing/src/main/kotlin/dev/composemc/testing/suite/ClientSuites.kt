package dev.composemc.testing.suite

/**
 * The two automated client suites. Each Minecraft adapter implements both, selected with `-Dcomposemc.suite=<id>`.
 * Gradle, the launch scripts and the summarizer use the same file names.
 */
enum class ClientSuite(val id: String, val report: String, val results: String) {
    ACCEPTANCE("acceptance", "composemc-acceptance.txt", "acceptance-results"),
    BENCHMARK("benchmark", "composemc-benchmark.txt", "benchmark-results");

    companion object {
        fun of(id: String): ClientSuite =
            requireNotNull(entries.firstOrNull { it.id == id }) {
                "Unknown client suite '$id'; expected one of ${entries.map { it.id }}"
            }
    }
}

/**
 * Correctness steps in execution order. Every adapter passes each step, in this order, before its report can say PASS,
 * so no Minecraft version can silently cover less than the others.
 */
enum class AcceptanceStep(val report: String) {
    RESOURCES("library and development translations loaded; loader environment matches the launch"),
    RENDERER("renderer backend matches CPU reference pixels"),
    WORLD("fresh flat creative test world loaded"),
    INVENTORY("native left/right container input, per-slot render hooks, server acknowledgement and screen release"),
    MENU_SYNC(
        "bounded multi-batch snapshot, fragmented action round trip and a native value both ways through one shared codec"
    ),
    CONFIG("config staging, scalar/list validation, save and restore"),
    PORT_INPUT("top-left pointer coordinates, premultiplied alpha, Unicode text entry and key translation"),
    PORT_SCALE("GUI scaling and framebuffer resize keep pixels in place"),
    PORT_RELOAD("resource reload keeps rendered pixels correct"),
    NATIVE_VISUAL("native item alpha, rotation, shape clipping, occlusion and repeated placement"),
    HUD_RENDER(
        "HUD layer: Compose and native item pixels over the world, drawing on beneath a screen that takes the input"
    ),
    HUD_VISIBILITY("hiding the GUI hides the HUD layer; showing it again resumes the same session"),
    HUD_RESIZE("GUI scale, framebuffer resize and resource reload keep the HUD session and its pixels in place"),
    HUD_RELEASE("leaving the world and close() release the HUD session; the next drawn frame opens a new one"),
    PREVIEW_OPEN("F8 key mapping opens the development preview"),
    RETAINED_FRAME("static UI reuses its retained frame"),
    TEXT_FIELD("text field focus, supplementary Unicode input and select-all delete"),
    MODAL("a clicked button opens a modal dialog and Escape closes it before the screen"),
    SESSION_RESIZE("window resize and GUI scale preserve the session"),
    LIST("100k list hit testing, wheel scrolling and texture reuse"),
    NATIVE_ITEMS("native item page: preview pixels, Minecraft drawing after Compose, preparation and grid hit testing"),
    TOOLTIPS(
        "native tooltips: delayed hover, exclusive replacement, rich bundle image, event cancellation, " +
            "click/scroll dismissal, pending/visible modal suppression, edge placement, GUI scales 2/3 and reload"
    ),
    NATIVE_SCROLL("10k-row native item scrolling with bounded preparation and retirement"),
    RELOAD("resource reload rebuilds one surface and preserves the session and item pixels"),
    FOCUS("logical focus loss hides tooltips and ignores input; focus return restores both"),
    CLOSE("close releases the session, surfaces and native images"),
    REOPEN("F8 reopens the preview and Escape releases it"),
    COMPONENTS(
        "Ore component pages: selection, menus, numbers, colors, tree, tooltips, windows, fields, slots, surfaces and items"
    ),
    THEME_RELOAD("resource themes merge packs, isolate mods, recover invalid layers and preserve live state"),
    STRESS("12 repeated screen opens/renders/closes release their sessions and surfaces"),
}

/** Records passed steps and refuses to report PASS unless every step passed in order. */
class AcceptanceLog {
    private val passed = mutableListOf<AcceptanceStep>()
    private val lines = mutableListOf<String>()
    val next: AcceptanceStep?
        get() = AcceptanceStep.entries.getOrNull(passed.size)

    fun pass(step: AcceptanceStep, detail: String? = null) {
        check(step == next) { "Acceptance step $step completed out of order; expected $next" }
        passed += step
        lines += if (detail.isNullOrBlank()) step.report else "${step.report} ($detail)"
    }

    fun report(header: String): String {
        check(next == null) { "Acceptance incomplete; missing ${AcceptanceStep.entries.drop(passed.size)}" }
        return "PASS $header\n" + lines.joinToString("\n") + "\n"
    }

    fun failure(header: String, error: Throwable): String =
        "FAIL $header at ${next ?: "REPORT"}: $error\n" + lines.joinToString("\n") + "\n\n" + error.stackTraceToString()
}
