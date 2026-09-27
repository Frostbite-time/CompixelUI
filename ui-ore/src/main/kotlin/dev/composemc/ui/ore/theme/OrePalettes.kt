package dev.composemc.ui.ore.theme

import androidx.compose.ui.graphics.Color

/** Built-in palettes. Light uses neutral metal panels and violet accents; Twilight uses deep navy and teal. */
object OrePalettes {
    val Light =
        OreColors(
            backdrop = Color(0xB8242730),
            panel = Color(0xFFD8D9DE),
            raised = Color(0xFFECECF0),
            hovered = Color(0xFFF6F5FA),
            edge = Color(0xFF727582),
            highlight = Color(0xFFF9F9FC),
            frameEdge = Color(0xFF353742),
            ledge = Color(0xFF999DA8),
            slot = Color(0xFFA9ADB8),
            slotEdge = Color(0xFF595D6B),
            markedSlot = Color(0xFFC8BDDE),
            slotHover = Color(0xFFDDD4EE),
            slotHoverEdge = Color(0xFF746091),
            text = Color(0xFF262935),
            mutedText = Color(0xFF535868),
            primary = Color(0xFF765596),
            primaryHover = Color(0xFF674887),
            primaryPressed = Color(0xFF593E77),
            primaryEdge = Color(0xFF3C294F),
            secondary = Color(0xFFF0F0F4),
            secondaryHover = Color(0xFFDCDCE5),
            secondaryPressed = Color(0xFFCACBD6),
            secondaryEdge = Color(0xFF737787),
            buttonBorder = Color(0xFF535763),
            secondaryButtonActive = Color(0xFFD3CDDF),
            secondaryButtonLightEdge = Color(0xFFFFFFFF),
            secondaryButtonDarkEdge = Color(0xFFE4E5EB),
            secondaryButtonCorner = Color(0xFFF8F8FC),
            switchTrack = Color(0xFF969BA9),
            trackFilledLight = Color(0xFFAA92C4),
            trackFilled = Color(0xFF765596),
            trackEmptyLight = Color(0xFFE3E5EB),
            trackEmpty = Color(0xFF969BA9),
            disabledEdge = Color(0xFFADB0BA),
            disabledText = Color(0xFF686E7A),
            ink = Color(0xFF262935),
            danger = Color(0xFFA33E48),
            dangerHover = Color(0xFF8B303B),
            dangerPressed = Color(0xFF772631),
            dangerEdge = Color(0xFF551F29),
            focus = Color(0xFF5A397C),
            onPrimary = Color(0xFFFAF8FF),
            onSecondary = Color(0xFF262935),
            onDanger = Color(0xFFFFF8F8),
        )

    /**
     * Deep navy panels with a teal accent. The primary and secondary families are generated as a theme file's `palette`
     * generates them; the brighter teal on filled tracks keeps sliders and progress bars vivid.
     */
    val Twilight =
        OreColors(
                backdrop = Color(0xD90B0E1A),
                panel = Color(0xFF1A1F33),
                raised = Color(0xFF252C48),
                hovered = Color(0xFF2E3658),
                edge = Color(0xFF0E1222),
                highlight = Color(0xFF3A4470),
                frameEdge = Color(0xFF06080F),
                ledge = Color(0xFF11152A),
                slot = Color(0xFF131829),
                slotEdge = Color(0xFF060810),
                text = Color(0xFFEEF3FF),
                mutedText = Color(0xFF9EABD0),
                buttonBorder = Color(0xFF0B0E1A),
                switchTrack = Color(0xFF4A5480),
                trackEmptyLight = Color(0xFF6572A3),
                trackEmpty = Color(0xFF4A5480),
                disabledEdge = Color(0xFF4F587C),
                disabledText = Color(0xFF6B7598),
            )
            .withPalette("primary", Color(0xFF177E9C))
            .withPalette("secondary", Color(0xFF3B4470))
            .copy(trackFilled = Color(0xFF2EA7C4), trackFilledLight = Color(0xFF62C0D6))
}
