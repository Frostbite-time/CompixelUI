package dev.composemc.ui.ore.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.internal.oreFrame
import dev.composemc.ui.ore.theme.OreTheme

enum class OreSurfaceStyle { Flat, Inset, Raised }

@Composable
fun OreSurface(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    bottomLedge: Dp = Dp.Unspecified,
    ledgeColor: Color = OreTheme.colors.ledge,
    frameEdge: Color = OreTheme.colors.frameEdge,
    drawTopEdge: Boolean = true,
    drawEndEdge: Boolean = true,
    drawBottomEdge: Boolean = true,
    drawStartEdge: Boolean = true,
    style: OreSurfaceStyle = OreSurfaceStyle.Flat,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = OreTheme.colors
    val fill = if (color != Color.Unspecified) color else if (style == OreSurfaceStyle.Raised) colors.raised else colors.panel
    val layoutDirection = LocalLayoutDirection.current
    val effectiveLedge = when {
        !drawBottomEdge || style == OreSurfaceStyle.Inset -> 0.dp
        bottomLedge.value.isNaN() -> if (style == OreSurfaceStyle.Raised) 2.dp else 0.dp
        else -> bottomLedge
    }
    require(effectiveLedge.value.isFinite() && effectiveLedge >= 0.dp)
    val frame = modifier.oreFrame(fill, frameEdge, colors.highlight, effectiveLedge, ledgeColor,
        drawTopEdge, drawEndEdge, drawBottomEdge, drawStartEdge, layoutDirection, style, colors.edge)
    if (style != OreSurfaceStyle.Flat) {
        Box(frame.padding(
            start = if (drawStartEdge) 1.dp else 0.dp,
            top = if (!drawTopEdge) 0.dp else if (style == OreSurfaceStyle.Inset) 3.dp else 2.dp,
            end = if (drawEndEdge) 1.dp else 0.dp,
            bottom = if (!drawBottomEdge) 0.dp else if (style == OreSurfaceStyle.Raised) 1.dp + effectiveLedge else 2.dp,
        ), content = content)
    } else if (effectiveLedge > 0.dp) {
        Box(frame.padding(
            start = if (drawStartEdge) 2.dp else 0.dp,
            top = if (drawTopEdge) 2.dp else 0.dp,
            end = if (drawEndEdge) 2.dp else 0.dp,
            bottom = (if (drawBottomEdge) 2.dp else 0.dp) + effectiveLedge,
        ), content = content)
    } else {
        Box(frame.padding(
            start = if (drawStartEdge) 1.dp else 0.dp,
            top = if (drawTopEdge) 1.dp else 0.dp,
            end = if (drawEndEdge) 1.dp else 0.dp,
            bottom = if (drawBottomEdge) 1.dp else 0.dp,
        ), content = content)
    }
}
