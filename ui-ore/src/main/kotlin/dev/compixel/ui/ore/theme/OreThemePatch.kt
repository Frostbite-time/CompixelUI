package dev.compixel.ui.ore.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** A validated file layer. Exact tokens override generated palette roles within the same layer. */
@Immutable
class OreThemePatch
private constructor(
    private val preset: String?,
    private val palette: Map<String, Color>,
    private val colors: Map<String, Color>,
) {
    fun applyTo(base: OreColors): OreColors {
        var result =
            when (preset) {
                "light" -> OrePalettes.Light
                "twilight" -> OrePalettes.Twilight
                "default" -> OreColors()
                else -> base
            }
        palette.forEach { (key, color) -> result = result.withPalette(key, color) }
        colors.forEach { (key, color) -> result = result.withToken(key, color) }
        return result
    }

    companion object {
        /** Parse the JSON object's neutral values. Invalid files fail as one layer, never partially apply. */
        fun parse(document: Map<String, Any?>): OreThemePatch {
            document.keys.forEach {
                require(it in setOf("format", "preset", "palette", "colors")) { "Unknown field: $it" }
            }
            val format = document["format"]
            require(format is Number && format.toDouble() == 1.0) { "format: expected 1" }
            val preset = document["preset"]
            require("preset" !in document || preset in setOf("default", "light", "twilight")) {
                "preset: expected default, light or twilight"
            }
            fun section(name: String, allowed: Set<String>): Map<String, Color> {
                if (name !in document) return emptyMap()
                val values = document[name]
                require(values is Map<*, *>) { "$name: expected an object" }
                return values.entries.associate { (key, value) ->
                    require(key is String && key in allowed) { "$name.$key: unknown color role" }
                    require(value is String && value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {
                        "$name.$key: expected #RRGGBB or #RRGGBBAA"
                    }
                    val bits = value.drop(1).toLong(16)
                    val argb = if (value.length == 7) bits or 0xFF000000L else (bits ushr 8) or ((bits and 255) shl 24)
                    key to Color(argb.toInt())
                }
            }
            return OreThemePatch(
                preset as String?,
                section("palette", setOf("primary", "secondary", "danger")),
                section("colors", tokens),
            )
        }

        /** Accepted exact color roles, matching [OreColors]. */
        val tokens: Set<String> =
            setOf(
                "backdrop",
                "panel",
                "raised",
                "hovered",
                "edge",
                "highlight",
                "frameEdge",
                "ledge",
                "slot",
                "slotEdge",
                "markedSlot",
                "slotHover",
                "slotHoverEdge",
                "text",
                "mutedText",
                "primary",
                "primaryHover",
                "primaryPressed",
                "primaryEdge",
                "secondary",
                "secondaryHover",
                "secondaryPressed",
                "secondaryEdge",
                "buttonBorder",
                "secondaryButtonActive",
                "secondaryButtonLightEdge",
                "secondaryButtonDarkEdge",
                "secondaryButtonCorner",
                "switchTrack",
                "trackFilledLight",
                "trackFilled",
                "trackEmptyLight",
                "trackEmpty",
                "disabledEdge",
                "disabledText",
                "ink",
                "danger",
                "dangerHover",
                "dangerPressed",
                "dangerEdge",
                "focus",
                "onPrimary",
                "onSecondary",
                "onDanger",
                "bevelLight",
            )
    }
}

private fun foreground(color: Color): Color = if (color.luminance() > .179f) Color(0xFF161820) else Color.White

/** Generates a role's whole state family from one base color, as a theme file's `palette` section does. */
internal fun OreColors.withPalette(key: String, color: Color): OreColors {
    val hover = lerp(color, Color.Black, .16f)
    val pressed = lerp(color, Color.Black, .26f)
    val border = lerp(color, Color.Black, .48f)
    return when (key) {
        "primary" ->
            copy(
                primary = color,
                primaryHover = hover,
                primaryPressed = pressed,
                primaryEdge = border,
                onPrimary = foreground(color),
                trackFilled = color,
                trackFilledLight = lerp(color, bevelLight, .28f),
                markedSlot = lerp(slot, color, .22f),
                slotHover = lerp(slot, color, .32f),
                slotHoverEdge = lerp(color, bevelLight, .45f),
            )
        "secondary" ->
            copy(
                secondary = color,
                secondaryHover = hover,
                secondaryPressed = pressed,
                secondaryEdge = border,
                onSecondary = foreground(color),
                secondaryButtonActive = pressed,
                secondaryButtonLightEdge = lerp(color, bevelLight, .60f),
                secondaryButtonDarkEdge = lerp(color, bevelLight, .32f),
                secondaryButtonCorner = lerp(color, bevelLight, .75f),
            )
        "danger" ->
            copy(
                danger = color,
                dangerHover = hover,
                dangerPressed = pressed,
                dangerEdge = border,
                onDanger = foreground(color),
            )
        else -> error("Unknown palette: $key")
    }
}

private fun OreColors.withToken(key: String, color: Color): OreColors =
    when (key) {
        "backdrop" -> copy(backdrop = color)
        "panel" -> copy(panel = color)
        "raised" -> copy(raised = color)
        "hovered" -> copy(hovered = color)
        "edge" -> copy(edge = color)
        "highlight" -> copy(highlight = color)
        "frameEdge" -> copy(frameEdge = color)
        "ledge" -> copy(ledge = color)
        "slot" -> copy(slot = color)
        "slotEdge" -> copy(slotEdge = color)
        "markedSlot" -> copy(markedSlot = color)
        "slotHover" -> copy(slotHover = color)
        "slotHoverEdge" -> copy(slotHoverEdge = color)
        "text" -> copy(text = color)
        "mutedText" -> copy(mutedText = color)
        "primary" -> copy(primary = color)
        "primaryHover" -> copy(primaryHover = color)
        "primaryPressed" -> copy(primaryPressed = color)
        "primaryEdge" -> copy(primaryEdge = color)
        "secondary" -> copy(secondary = color)
        "secondaryHover" -> copy(secondaryHover = color)
        "secondaryPressed" -> copy(secondaryPressed = color)
        "secondaryEdge" -> copy(secondaryEdge = color)
        "buttonBorder" -> copy(buttonBorder = color)
        "secondaryButtonActive" -> copy(secondaryButtonActive = color)
        "secondaryButtonLightEdge" -> copy(secondaryButtonLightEdge = color)
        "secondaryButtonDarkEdge" -> copy(secondaryButtonDarkEdge = color)
        "secondaryButtonCorner" -> copy(secondaryButtonCorner = color)
        "switchTrack" -> copy(switchTrack = color)
        "trackFilledLight" -> copy(trackFilledLight = color)
        "trackFilled" -> copy(trackFilled = color)
        "trackEmptyLight" -> copy(trackEmptyLight = color)
        "trackEmpty" -> copy(trackEmpty = color)
        "disabledEdge" -> copy(disabledEdge = color)
        "disabledText" -> copy(disabledText = color)
        "ink" -> copy(ink = color)
        "danger" -> copy(danger = color)
        "dangerHover" -> copy(dangerHover = color)
        "dangerPressed" -> copy(dangerPressed = color)
        "dangerEdge" -> copy(dangerEdge = color)
        "focus" -> copy(focus = color)
        "onPrimary" -> copy(onPrimary = color)
        "onSecondary" -> copy(onSecondary = color)
        "onDanger" -> copy(onDanger = color)
        "bevelLight" -> copy(bevelLight = color)
        else -> error("Unknown color: $key")
    }
