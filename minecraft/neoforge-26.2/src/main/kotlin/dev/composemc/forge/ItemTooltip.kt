@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.composemc.forge

import androidx.compose.foundation.TooltipArea
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import dev.composemc.bridge.drawNativeImage
import kotlinx.coroutines.delay

/**
 * A native rich item tooltip in a Compose popup. Content keeps its own input behavior.
 * Native item/font/component access stays on the game thread; Compose owns hover and placement.
 */
@Composable
fun MinecraftItemTooltip(
    icon: ItemIcon,
    modifier: Modifier = Modifier,
    delayMillis: Int = 500,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    require(delayMillis >= 0)
    val mailbox = checkNotNull(LocalItemTooltips.current) { "MinecraftItemTooltip requires a ComposeScreen" }
    TooltipArea(
        modifier = modifier,
        // Attach the popup at hover entry. A modal opened during the delay must stay above it.
        delayMillis = 0,
        tooltipPlacement = ItemTooltipPlacement,
        tooltip = {
            val focused = LocalWindowInfo.current.isWindowFocused
            // Only the tooltip body restarts; remembered state inside user content survives.
            key(icon, mailbox.interactionEpoch, focused, enabled, delayMillis) {
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (enabled && focused) {
                        delay(delayMillis.toLong())
                        ready = true
                    }
                }
                if (ready) NativeTooltipImage(icon, mailbox)
            }
        },
        content = content,
    )
}

@Composable
private fun NativeTooltipImage(icon: ItemIcon, mailbox: ItemTooltipMailbox) {
    val request = remember(icon) { ItemTooltipRequest(icon) }
    val paint = remember { Paint() }
    DisposableEffect(mailbox, request) {
        mailbox.show(request)
        onDispose { mailbox.hide(request) }
    }
    Layout(content = {}, modifier = Modifier
        .semantics { contentDescription = icon.description }
        .onGloballyPositioned { mailbox.position(request, it.boundsInWindow()) }
        .drawBehind { mailbox.imageFor(request)?.let { drawNativeImage(it, paint) } }
    ) { _, constraints ->
        val info = mailbox.imageFor(request)?.imageInfo
        layout(constraints.constrainWidth(info?.width ?: 0), constraints.constrainHeight(info?.height ?: 0)) {}
    }
}

internal class ItemTooltipRequest(val icon: ItemIcon)
internal val LocalItemTooltips = staticCompositionLocalOf<ItemTooltipMailbox?> { null }

/** A single request/result slot, confined to the Compose EDT. */
internal class ItemTooltipMailbox {
    private data class Result(val request: ItemTooltipRequest, val image: org.jetbrains.skia.Image)
    var interactionEpoch by mutableLongStateOf(0)
        private set
    var request: ItemTooltipRequest? = null
        private set
    private var result by mutableStateOf<Result?>(null)
    var bounds: Rect? = null
        private set

    fun show(value: ItemTooltipRequest) {
        checkThread()
        request = value
        result = null
        bounds = null
    }
    fun hide(value: ItemTooltipRequest) {
        checkThread()
        if (request === value) { request = null; clearImage() }
    }
    fun imageFor(value: ItemTooltipRequest): org.jetbrains.skia.Image? = result?.takeIf { it.request === value }?.image
    fun publish(value: ItemTooltipRequest, image: org.jetbrains.skia.Image?): Boolean {
        checkThread()
        if (request !== value) return false
        result = image?.let { Result(value, it) }
        if (image == null) bounds = null
        return true
    }
    fun position(value: ItemTooltipRequest, bounds: Rect) {
        if (request === value && result?.request === value) this.bounds = bounds
    }
    fun clearImage() { checkThread(); result = null; bounds = null }
    fun dismiss() {
        checkThread()
        interactionEpoch++
        request = null
        clearImage()
    }
    private fun checkThread() = check(java.awt.EventQueue.isDispatchThread()) { "Tooltip mailbox accessed outside EDT" }
}
