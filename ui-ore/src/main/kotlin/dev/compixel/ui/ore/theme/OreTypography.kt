package dev.compixel.ui.ore.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Ore's text styles. A null font family inherits the theme's reloadable Compixel resource font. */
@Immutable
data class OreTypography(
    val body: TextStyle =
        TextStyle(
            fontSize = 8.sp,
            lineHeight = 11.sp,
            fontFeatureSettings = "liga=0,calt=0",
        ),
    val title: TextStyle = body.copy(fontSize = 10.sp, lineHeight = 13.sp),
    val caption: TextStyle = body.copy(fontSize = 6.sp, lineHeight = 9.sp),
)
