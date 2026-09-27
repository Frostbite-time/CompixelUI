package dev.composemc.ui.ore.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Ore-inspired defaults. These values and widgets have no game/loader dependency. */
@Immutable
data class OreColors(
    val backdrop: Color = Color(0xD9141516),
    val panel: Color = Color(0xFF313233),
    val raised: Color = Color(0xFF48494A),
    val hovered: Color = Color(0xFF58595A),
    val edge: Color = Color(0xFF1E1E1F),
    val highlight: Color = Color(0xFF6D6E70),
    // Window outer frame: darker than the generic control edge for contrast.
    val frameEdge: Color = Color(0xFF101112),
    // Bottom-ledge fill: midpoint between outer edge and inner highlight,
    // following OreUI's neutral-bevel-shadow (surface darkened ~30%).
    val ledge: Color = Color(0xFF242426),
    val slot: Color = Color(0xFF252627),
    val slotEdge: Color = Color(0xFF101112),
    val markedSlot: Color = Color(0xFF2C3C2C),
    val slotHover: Color = Color(0xFF3A403C),
    val slotHoverEdge: Color = Color(0xFF9EAA9E),
    val text: Color = Color(0xFFF2F3F4),
    val mutedText: Color = Color(0xFFB8BABD),
    val primary: Color = Color(0xFF3C8527),
    val primaryHover: Color = Color(0xFF2F6B20),
    val primaryPressed: Color = Color(0xFF2B611C),
    val primaryEdge: Color = Color(0xFF214515),
    val secondary: Color = Color(0xFFD0D1D4),
    val secondaryHover: Color = Color(0xFFAEB0B4),
    val secondaryPressed: Color = Color(0xFFADAFB4),
    val secondaryEdge: Color = Color(0xFF58585A),
    // The secondary button has a fixed, pixel-art face rather than a derived gradient.
    // Keeping these as theme tokens lets consumers retheme the complete construction.
    val buttonBorder: Color = Color(0xFF1E1E1F),
    val secondaryButtonActive: Color = Color(0xFFB1B2B5),
    val secondaryButtonLightEdge: Color = Color(0xFFECEDEE),
    val secondaryButtonDarkEdge: Color = Color(0xFFE3E3E5),
    val secondaryButtonCorner: Color = Color(0xFFF4F4F5),
    val switchTrack: Color = Color(0xFF8C8D90),
    // Slider/track fills. The track body mirrors the switch thumb: the outer
    // 1px frame is a plain edge, the inner 1px ring is a light edge and the
    // center is the saturated fill. Tokens live here so switch and slider stay
    // in sync without sharing drawing code.
    val trackFilledLight: Color = Color(0xFF48733C),
    val trackFilled: Color = Color(0xFF3C8527),
    val trackEmptyLight: Color = Color(0xFFA3A4A6),
    val trackEmpty: Color = Color(0xFF8C8D90),
    val disabledEdge: Color = Color(0xFF8E9094),
    val disabledText: Color = Color(0xFF77797D),
    val ink: Color = Color(0xFF242426),
    val danger: Color = Color(0xFFAD3238),
    val dangerHover: Color = Color(0xFF8B2930),
    val dangerPressed: Color = Color(0xFF812126),
    val dangerEdge: Color = Color(0xFF591A1F),
    val focus: Color = Color.White,
    val onPrimary: Color = text,
    val onSecondary: Color = ink,
    val onDanger: Color = text,
    val bevelLight: Color = Color.White,
)
