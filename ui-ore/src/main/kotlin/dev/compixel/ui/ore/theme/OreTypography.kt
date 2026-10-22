package dev.compixel.ui.ore.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import dev.compixel.ui.ore.internal.CompixelFont

// Compixel: Monocraft, with GNU Unifont for the characters Monocraft lacks. tools/build_font.java builds it.
private object OreFonts {
    val pixel by lazy { FontFamily(Font("compixel-font", CompixelFont::data)) }
}

@Immutable
data class OreTypography(
    val body: TextStyle =
        TextStyle(
            fontFamily = OreFonts.pixel,
            fontSize = 8.sp,
            lineHeight = 11.sp,
            fontFeatureSettings = "liga=0,calt=0",
        ),
    val title: TextStyle = body.copy(fontSize = 10.sp, lineHeight = 13.sp),
    val caption: TextStyle = body.copy(fontSize = 6.sp, lineHeight = 9.sp),
)
