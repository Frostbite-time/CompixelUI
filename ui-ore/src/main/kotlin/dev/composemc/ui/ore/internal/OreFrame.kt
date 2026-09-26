package dev.composemc.ui.ore.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.layout.OreSurfaceStyle
import kotlin.math.roundToInt

internal fun Modifier.oreFrame(
    fill: Color,
    edge: Color,
    light: Color,
    bottomLedge: Dp = 0.dp,
    ledgeColor: Color = edge,
    drawTopEdge: Boolean = true,
    drawEndEdge: Boolean = true,
    drawBottomEdge: Boolean = true,
    drawStartEdge: Boolean = true,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    style: OreSurfaceStyle = OreSurfaceStyle.Flat,
    shadow: Color = edge,
): Modifier = drawBehind {
    val pixel = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
    val drawLeftEdge = if (layoutDirection == LayoutDirection.Ltr) drawStartEdge else drawEndEdge
    val drawRightEdge = if (layoutDirection == LayoutDirection.Ltr) drawEndEdge else drawStartEdge
    val left = if (drawLeftEdge) pixel else 0f
    val top = if (drawTopEdge) pixel else 0f
    val right = if (drawRightEdge) pixel else 0f
    val bottom = if (drawBottomEdge) pixel else 0f
    val innerWidth = (size.width - left - right).coerceAtLeast(0f)
    val innerHeight = (size.height - top - bottom).coerceAtLeast(0f)
    val ledge = if (drawBottomEdge) bottomLedge.toPx().roundToInt().coerceAtLeast(0).toFloat() else 0f

    drawRect(fill)
    if (drawTopEdge) drawRect(edge, Offset.Zero, Size(size.width, pixel))
    if (drawRightEdge) drawRect(edge, Offset(size.width - pixel, 0f), Size(pixel, size.height))
    if (drawBottomEdge) drawRect(edge, Offset(0f, size.height - pixel), Size(size.width, pixel))
    if (drawLeftEdge) drawRect(edge, Offset.Zero, Size(pixel, size.height))
    drawRect(fill, Offset(left, top), Size(innerWidth, innerHeight))

    if (style != OreSurfaceStyle.Flat) {
        // Depth is carried by horizontal bands. The vertical edges are only the casing.
        if (drawTopEdge) {
            val thickness = if (style == OreSurfaceStyle.Inset) 2.dp.roundToPx().toFloat() else pixel
            drawRect(
                if (style == OreSurfaceStyle.Inset) shadow else light,
                Offset(left, top),
                Size(innerWidth, thickness.coerceAtMost(innerHeight)),
            )
        }
        if (drawBottomEdge) {
            val thickness = (if (style == OreSurfaceStyle.Raised) ledge else pixel).coerceAtMost(innerHeight)
            drawRect(
                if (style == OreSurfaceStyle.Raised) ledgeColor else light,
                Offset(left, size.height - bottom - thickness),
                Size(innerWidth, thickness),
            )
        }
        return@drawBehind
    }

    val upperEdge = if (style == OreSurfaceStyle.Inset) shadow else light
    val lowerEdge = if (style == OreSurfaceStyle.Raised) shadow else light
    if (drawTopEdge) drawRect(upperEdge, Offset(left, top), Size(innerWidth, pixel))
    if (drawLeftEdge) drawRect(upperEdge, Offset(left, top), Size(pixel, innerHeight))
    if (drawRightEdge) drawRect(lowerEdge, Offset(size.width - right - pixel, top), Size(pixel, innerHeight))
    if (drawBottomEdge) {
        drawRect(lowerEdge, Offset(left, size.height - bottom - ledge - pixel), Size(innerWidth, pixel))
        if (ledge > 0f) drawRect(ledgeColor, Offset(left, size.height - bottom - ledge), Size(innerWidth, ledge))
    }

    val faceLeft = left + if (drawLeftEdge) pixel else 0f
    val faceTop = top + if (drawTopEdge) pixel else 0f
    val faceRight = right + if (drawRightEdge) pixel else 0f
    val faceBottom = if (drawBottomEdge) bottom + pixel + ledge else 0f
    drawRect(
        fill,
        Offset(faceLeft, faceTop),
        Size(
            (size.width - faceLeft - faceRight).coerceAtLeast(0f),
            (size.height - faceTop - faceBottom).coerceAtLeast(0f),
        ),
    )
}

/** An independent focus/error ring: never turn a recessed shadow into a white bevel. */
internal fun Modifier.oreOutline(color: Color?): Modifier =
    if (color == null) this
    else
        drawBehind {
            val pixel = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
            drawRect(
                color,
                Offset(pixel / 2, pixel / 2),
                Size((size.width - pixel).coerceAtLeast(0f), (size.height - pixel).coerceAtLeast(0f)),
                style = Stroke(pixel),
            )
        }
