package dev.compixel.ui.ore.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.compixel.ui.theme.BuiltInScheme
import dev.compixel.ui.theme.ColorRole
import dev.compixel.ui.theme.ColorSchema

/**
 * Ore's colors, inspired by Minecraft's Ore UI. Base colors give a scheme its look; derived colors, such as a button's
 * hover, pressed and edge shades or a slot's marked and hovered fills, follow their base color unless a scheme sets
 * them. The defaults are the original dark look; [builtIn] adds Light, with neutral metal panels and violet accents,
 * and Twilight, with deep navy panels and teal accents.
 */
object OreColors : ColorSchema("ore") {
    val backdrop = color("backdrop", "surfaces", Color(0xD9141516))
    val scrim = color("scrim", "surfaces", Color(0x99000000))
    val panel = color("panel", "surfaces", Color(0xFF313233))
    val raised = color("raised", "surfaces", Color(0xFF48494A))
    val hovered = color("hovered", "surfaces", Color(0xFF58595A))
    val edge = color("edge", "surfaces", Color(0xFF1E1E1F))
    // The light edge of panels, slots and similar.
    val highlight = color("highlight", "surfaces", Color(0xFF6D6E70))
    // A window's outer frame: darker than the generic control edge for contrast.
    val frameEdge = color("frameEdge", "surfaces", Color(0xFF101112))
    // The strip beneath a window or panel, as Ore UI shades its neutral bevel.
    val ledge = color("ledge", "surfaces", Color(0xFF242426))
    val bevelLight = color("bevelLight", "surfaces", Color.White)
    val text = color("text", "text", Color(0xFFF2F3F4))
    val mutedText = color("mutedText", "text", Color(0xFFB8BABD))
    val disabledText = color("disabledText", "text", Color(0xFF77797D))
    val disabledEdge = color("disabledEdge", "text", Color(0xFF8E9094))
    // A dark text color for mods to use; it doesn't change onSecondary.
    val ink = color("ink", "text", Color(0xFF242426))
    val focus = color("focus", "text", Color.White)
    val slot = color("slot", "slots", Color(0xFF252627))
    val slotEdge = color("slotEdge", "slots", Color(0xFF101112))
    // A slot's amount and its one-pixel shadow: white over blue-gray, legible over any item in every scheme.
    val slotAmount = color("slotAmount", "slots", Color.White)
    val slotAmountShadow = color("slotAmountShadow", "slots", Color(0xFF413F54))
    val primary = color("primary", "primary", Color(0xFF3C8527))
    val secondary = color("secondary", "secondary", Color(0xFFD0D1D4))
    // The secondary button has a fixed pixel-art face; its border is a color of its own.
    val buttonBorder = color("buttonBorder", "secondary", Color(0xFF1E1E1F))
    val danger = color("danger", "danger", Color(0xFFAD3238))
    // A slider track mirrors the switch: a plain outer edge, a light inner ring and the fill.
    val switchTrack = color("switchTrack", "controls", Color(0xFF8C8D90))
    val trackEmpty = color("trackEmpty", "controls", Color(0xFF8C8D90))
    val trackEmptyLight = color("trackEmptyLight", "controls", Color(0xFFA3A4A6))

    val selection = derived("selection", "text", primary) { it[primary].copy(alpha = .4f) }
    val markedSlot =
        derived("markedSlot", "slots", slot, primary, default = Color(0xFF2C3C2C)) { lerp(it[slot], it[primary], .22f) }
    val slotHover =
        derived("slotHover", "slots", slot, primary, default = Color(0xFF3A403C)) { lerp(it[slot], it[primary], .32f) }
    val slotHoverEdge =
        derived("slotHoverEdge", "slots", primary, bevelLight, default = Color(0xFF9EAA9E)) {
            lerp(it[primary], it[bevelLight], .45f)
        }
    val primaryHover = shade("primaryHover", "primary", primary, HOVER, Color(0xFF2F6B20))
    val primaryPressed = shade("primaryPressed", "primary", primary, PRESSED, Color(0xFF2B611C))
    val primaryEdge = shade("primaryEdge", "primary", primary, EDGE, Color(0xFF214515))
    val onPrimary = derived("onPrimary", "primary", primary, default = Color(0xFFF2F3F4)) { foreground(it[primary]) }
    val trackFilled = derived("trackFilled", "primary", primary, default = Color(0xFF3C8527)) { it[primary] }
    val trackFilledLight =
        derived("trackFilledLight", "primary", primary, bevelLight, default = Color(0xFF48733C)) {
            lerp(it[primary], it[bevelLight], .28f)
        }
    val secondaryHover = shade("secondaryHover", "secondary", secondary, HOVER, Color(0xFFAEB0B4))
    val secondaryPressed = shade("secondaryPressed", "secondary", secondary, PRESSED, Color(0xFFADAFB4))
    val secondaryEdge = shade("secondaryEdge", "secondary", secondary, EDGE, Color(0xFF58585A))
    val onSecondary =
        derived("onSecondary", "secondary", secondary, default = Color(0xFF242426)) { foreground(it[secondary]) }
    val secondaryButtonActive = shade("secondaryButtonActive", "secondary", secondary, PRESSED, Color(0xFFB1B2B5))
    val secondaryButtonLightEdge = bevel("secondaryButtonLightEdge", .60f, Color(0xFFECEDEE))
    val secondaryButtonDarkEdge = bevel("secondaryButtonDarkEdge", .32f, Color(0xFFE3E3E5))
    val secondaryButtonCorner = bevel("secondaryButtonCorner", .75f, Color(0xFFF4F4F5))
    val dangerHover = shade("dangerHover", "danger", danger, HOVER, Color(0xFF8B2930))
    val dangerPressed = shade("dangerPressed", "danger", danger, PRESSED, Color(0xFF812126))
    val dangerEdge = shade("dangerEdge", "danger", danger, EDGE, Color(0xFF591A1F))
    val onDanger = derived("onDanger", "danger", danger, default = Color(0xFFF2F3F4)) { foreground(it[danger]) }

    override val builtIn =
        listOf(
            BuiltInScheme("default", "compixel.scheme.default"),
            BuiltInScheme(
                "light",
                "compixel.scheme.light",
                mapOf(
                    backdrop to Color(0xB8242730),
                    panel to Color(0xFFD8D9DE),
                    raised to Color(0xFFECECF0),
                    hovered to Color(0xFFF6F5FA),
                    edge to Color(0xFF727582),
                    highlight to Color(0xFFF9F9FC),
                    frameEdge to Color(0xFF353742),
                    ledge to Color(0xFF999DA8),
                    slot to Color(0xFFA9ADB8),
                    slotEdge to Color(0xFF595D6B),
                    markedSlot to Color(0xFFC8BDDE),
                    slotHover to Color(0xFFDDD4EE),
                    slotHoverEdge to Color(0xFF746091),
                    text to Color(0xFF262935),
                    mutedText to Color(0xFF535868),
                    primary to Color(0xFF765596),
                    primaryHover to Color(0xFF674887),
                    primaryPressed to Color(0xFF593E77),
                    primaryEdge to Color(0xFF3C294F),
                    secondary to Color(0xFFF0F0F4),
                    secondaryHover to Color(0xFFDCDCE5),
                    secondaryPressed to Color(0xFFCACBD6),
                    secondaryEdge to Color(0xFF737787),
                    buttonBorder to Color(0xFF535763),
                    secondaryButtonActive to Color(0xFFD3CDDF),
                    secondaryButtonLightEdge to Color(0xFFFFFFFF),
                    secondaryButtonDarkEdge to Color(0xFFE4E5EB),
                    secondaryButtonCorner to Color(0xFFF8F8FC),
                    switchTrack to Color(0xFF969BA9),
                    trackFilledLight to Color(0xFFAA92C4),
                    trackFilled to Color(0xFF765596),
                    trackEmptyLight to Color(0xFFE3E5EB),
                    trackEmpty to Color(0xFF969BA9),
                    disabledEdge to Color(0xFFADB0BA),
                    disabledText to Color(0xFF686E7A),
                    ink to Color(0xFF262935),
                    danger to Color(0xFFA33E48),
                    dangerHover to Color(0xFF8B303B),
                    dangerPressed to Color(0xFF772631),
                    dangerEdge to Color(0xFF551F29),
                    focus to Color(0xFF5A397C),
                    onPrimary to Color(0xFFFAF8FF),
                    onSecondary to Color(0xFF262935),
                    onDanger to Color(0xFFFFF8F8),
                ),
            ),
            // The primary and secondary families follow their base colors; the brighter teal on filled tracks keeps
            // sliders and progress bars vivid.
            BuiltInScheme(
                "twilight",
                "compixel.scheme.twilight",
                mapOf(
                    backdrop to Color(0xD90B0E1A),
                    panel to Color(0xFF1A1F33),
                    raised to Color(0xFF252C48),
                    hovered to Color(0xFF2E3658),
                    edge to Color(0xFF0E1222),
                    highlight to Color(0xFF3A4470),
                    frameEdge to Color(0xFF06080F),
                    ledge to Color(0xFF11152A),
                    slot to Color(0xFF131829),
                    slotEdge to Color(0xFF060810),
                    text to Color(0xFFEEF3FF),
                    mutedText to Color(0xFF9EABD0),
                    buttonBorder to Color(0xFF0B0E1A),
                    switchTrack to Color(0xFF4A5480),
                    trackEmptyLight to Color(0xFF6572A3),
                    trackEmpty to Color(0xFF4A5480),
                    disabledEdge to Color(0xFF4F587C),
                    disabledText to Color(0xFF6B7598),
                    primary to Color(0xFF177E9C),
                    secondary to Color(0xFF3B4470),
                    trackFilled to Color(0xFF2EA7C4),
                    trackFilledLight to Color(0xFF62C0D6),
                    onDanger to Color(0xFFEEF3FF),
                ),
            ),
        )

    private fun shade(key: String, group: String, base: ColorRole, amount: Float, default: Color) =
        derived(key, group, base, default = default) { lerp(it[base], Color.Black, amount) }

    private fun bevel(key: String, amount: Float, default: Color) =
        derived(key, "secondary", secondary, bevelLight, default = default) {
            lerp(it[secondary], it[bevelLight], amount)
        }
}

private const val HOVER = .16f
private const val PRESSED = .26f
private const val EDGE = .48f

/** Dark text on light colors, white on dark ones. */
private fun foreground(color: Color): Color = if (color.luminance() > .179f) Color(0xFF161820) else Color.White
