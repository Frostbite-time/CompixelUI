@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package dev.composemc.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as LayoutRect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.composemc.platform.*
import dev.composemc.render.RecordedFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import androidx.navigationevent.NavigationEventInput

/** The only module permitted to import Compose's internal embedding API. */
class SceneBridge(viewport: Viewport, clipboard: ClipboardPort) : AutoCloseable {
    private val dirty = AtomicBoolean(true)
    private var closed = false
    private var viewport = viewport
    private var generation = 0L
    private var lastFrameTime = Long.MIN_VALUE
    private var settleLayout = true
    private val buttons = mutableSetOf<MouseButton>()
    private val textInput = SessionTextInput()
    private val info = SessionWindowInfo(viewport)
    private val owners = DefaultArchitectureComponentsOwner(enforceMainThread = false)
    private val backInput = SessionBackInput()
    private val platform = object : PlatformContext.Empty() {
        override val windowInfo: WindowInfo = info
        override val isWindowTransparent = true
        override val architectureComponentsOwner = owners
        override val textInputService: PlatformTextInputService = textInput
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            textInput.startRequest(request)
            try {
                awaitCancellation()
            } finally {
                textInput.endRequest(request)
            }
        }
    }
    private val recomposer = FrameRecomposer(Dispatchers.Swing) { dirty.set(true) }
    private val scene = CanvasLayersComposeScene(
        frameRecomposer = recomposer,
        density = Density(viewport.density),
        size = IntSize(viewport.width, viewport.height),
        platformContext = platform,
        invalidateLayout = { dirty.set(true) },
        invalidateDraw = { dirty.set(true) },
    )
    private val clipboardManager = object : ClipboardManager {
        override fun getText(): AnnotatedString = AnnotatedString(clipboard.readText())
        override fun setText(annotatedString: AnnotatedString) = clipboard.writeText(annotatedString.text)
    }
    private val platformClipboard = object : Clipboard {
        override val nativeClipboard: Any = clipboard
        override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(clipboard.readText()))
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val text = clipEntry?.asAwtTransferable?.let {
                if (it.isDataFlavorSupported(DataFlavor.stringFlavor)) it.getTransferData(DataFlavor.stringFlavor) as? String else null
            }
            clipboard.writeText(text.orEmpty())
        }
    }

    init {
        ComposeThread.check()
        owners.setLifecycleState(androidx.lifecycle.Lifecycle.State.RESUMED)
        owners.navigationEventDispatcherOwner.navigationEventDispatcher.addInput(backInput)
    }

    private fun checkOpen() {
        ComposeThread.check()
        check(!closed) { "Scene is closed" }
    }

    fun setContent(content: @Composable () -> Unit) {
        checkOpen()
        settleLayout = true
        scene.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboardManager, LocalClipboard provides platformClipboard) { content() }
        }
    }

    fun resize(viewport: Viewport) {
        checkOpen()
        if (viewport == this.viewport) return
        this.viewport = viewport
        info.containerSize = IntSize(viewport.width, viewport.height)
        scene.density = Density(viewport.density)
        scene.size = IntSize(viewport.width, viewport.height)
        settleLayout = true
        dirty.set(true)
    }

    fun configure(rtl: Boolean, layoutBounds: Boolean) {
        checkOpen()
        scene.layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        scene.showLayoutBounds = layoutBounds
        dirty.set(true)
    }

    fun invalidate() { checkOpen(); dirty.set(true) }

    fun setFocused(focused: Boolean) {
        checkOpen()
        if (info.isWindowFocused == focused) return
        info.isWindowFocused = focused
        if (!focused) {
            // An unconfirmed composition is discarded, not left behind in the field as typed text.
            textInput.setComposing(null)
            buttons.clear()
            scene.cancelPointerInput()
            scene.focusManager.releaseFocus()
            textInput.stopInput()
        }
        owners.setLifecycleState(if (focused) androidx.lifecycle.Lifecycle.State.RESUMED else androidx.lifecycle.Lifecycle.State.STARTED)
    }

    fun recordFrame(timeNanos: Long): RecordedFrame? {
        checkOpen()
        require(timeNanos >= lastFrameTime) { "Frame clock must be monotonic" }
        lastFrameTime = timeNanos
        if (!dirty.getAndSet(false) && !recomposer.hasPendingWork() && !scene.hasPendingMeasureOrLayout && !scene.hasPendingDraw) return null
        if (settleLayout) {
            settleLayout = false
            // Size/placement callbacks may feed state back into composition. Resolve that
            // startup work before exposing a picture, using the same animation timestamp.
            // Observe layout writes, not hasPendingWork(): animations can await frames forever.
            for (pass in 0 until 4) {
                recomposer.performFrame(timeNanos)
                var layoutWroteState = false
                Snapshot.observe(writeObserver = { layoutWroteState = true }) { scene.measureAndLayout() }
                if (!layoutWroteState) break
                Snapshot.sendApplyNotifications()
            }
        } else {
            recomposer.performFrame(timeNanos)
            scene.measureAndLayout()
        }
        PictureRecorder().use { recorder ->
            val canvas = recorder.beginRecording(Rect.makeWH(viewport.width.toFloat(), viewport.height.toFloat()))
            // Geometry and drawing stay on the EDT. The adapter may prepare native images
            // between recordings, then request one bounded replacement frame.
            scene.draw(canvas.asComposeCanvas())
            return RecordedFrame(++generation, viewport, recorder.finishRecordingAsPicture())
        }
    }

    fun pointer(event: PointerInput): Boolean {
        checkOpen()
        if (!info.isWindowFocused) return false
        prepareForInput()
        if (event.action == PointerAction.PRESS) event.button?.let(buttons::add)
        if (event.action == PointerAction.RELEASE) event.button?.let(buttons::remove)
        val type = when (event.action) {
            PointerAction.MOVE -> PointerEventType.Move
            PointerAction.PRESS -> PointerEventType.Press
            PointerAction.RELEASE -> PointerEventType.Release
            PointerAction.SCROLL -> PointerEventType.Scroll
            PointerAction.EXIT -> PointerEventType.Exit
        }
        scene.sendPointerEvent(
            eventType = type,
            position = Offset(event.x, event.y),
            scrollDelta = Offset(event.scrollX, event.scrollY),
            timeMillis = event.timeMillis,
            buttons = PointerButtons(
                isPrimaryPressed = MouseButton.LEFT in buttons,
                isSecondaryPressed = MouseButton.RIGHT in buttons,
                isTertiaryPressed = MouseButton.MIDDLE in buttons,
            ),
            keyboardModifiers = PointerKeyboardModifiers(
                isShiftPressed = event.modifiers.shift, isCtrlPressed = event.modifiers.control,
                isAltPressed = event.modifiers.alt, isMetaPressed = event.modifiers.meta,
            ),
            button = when (event.button) {
                MouseButton.LEFT -> PointerButton.Primary
                MouseButton.RIGHT -> PointerButton.Secondary
                MouseButton.MIDDLE -> PointerButton.Tertiary
                null -> null
            },
        )
        // Screen mode owns the pointer region; this is NOT a HUD hit-test result.
        // Compose keeps the detailed result internal in 1.12.0; the host accepts the event
        // once it has routed it to the active scene and prevents it reaching the game layer.
        return true
    }

    fun key(event: KeyInput): Boolean {
        checkOpen()
        if (!info.isWindowFocused) return false
        prepareForInput()
        val consumed = scene.sendKeyEvent(KeyEvent(
            key = event.key.composeKey(),
            type = if (event.pressed) KeyEventType.KeyDown else KeyEventType.KeyUp,
            isShiftPressed = event.modifiers.shift, isCtrlPressed = event.modifiers.control,
            isAltPressed = event.modifiers.alt, isMetaPressed = event.modifiers.meta,
        ))
        if (consumed) return true
        if (event.pressed && event.key == UiKey.ESCAPE && backInput.back()) return true
        if (event.pressed && event.key == UiKey.TAB) {
            return scene.focusManager.takeFocus(if (event.modifiers.shift) FocusDirection.Previous else FocusDirection.Next)
        }
        return false
    }

    fun commitText(text: String): Boolean {
        checkOpen()
        prepareForInput()
        return info.isWindowFocused && text.isNotEmpty() && textInput.commit(text)
    }

    /** Shows [text] as the focused field's composition, replacing the previous one; null removes it. */
    fun setComposingText(text: ComposingText?): Boolean {
        checkOpen()
        prepareForInput()
        return info.isWindowFocused && textInput.setComposing(text)
    }

    private fun prepareForInput() {
        // Several GLFW events can arrive between rendered frames. Apply selection/state writes
        // before the next event, without advancing animation time beyond the last host frame.
        if (recomposer.hasPendingWork()) {
            recomposer.performFrame(if (lastFrameTime == Long.MIN_VALUE) 0L else lastFrameTime)
        }
    }

    val diagnosticThread: String get() { checkOpen(); return Thread.currentThread().name }
    val hasTextInputFocus: Boolean get() {
        checkOpen()
        prepareForInput()
        return textInputFocused
    }
    /** [hasTextInputFocus] as the scene stands, without applying pending state (and animation frames) first. */
    val textInputFocused: Boolean get() { checkOpen(); return info.isWindowFocused && textInput.active }
    /** The focused field's caret while [textInputFocused], as the scene stands. */
    val textInputArea: TextInputArea? get() {
        if (!textInputFocused) return null
        val caret = textInput.focusedArea ?: return null
        return TextInputArea(caret.left, caret.top, caret.right, caret.bottom)
    }

    override fun close() {
        ComposeThread.check()
        if (closed) return
        closed = true
        try {
            scene.cancelPointerInput()
            textInput.stopInput()
            scene.close()
        } finally {
            try {
                recomposer.close()
            } finally {
                owners.navigationEventDispatcherOwner.navigationEventDispatcher.removeInput(backInput)
                owners.setLifecycleState(androidx.lifecycle.Lifecycle.State.DESTROYED)
                owners.viewModelStore.clear()
                owners.navigationEventDispatcherOwner.navigationEventDispatcher.dispose()
            }
        }
    }
}

private class SessionBackInput : NavigationEventInput() {
    private var enabled = false
    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) { enabled = hasEnabledHandlers }
    fun back(): Boolean {
        if (!enabled) return false
        dispatchOnBackCompleted()
        return true
    }
}

private class SessionWindowInfo(viewport: Viewport) : WindowInfo {
    override var isWindowFocused by mutableStateOf(true)
    override var containerSize by mutableStateOf(IntSize(viewport.width, viewport.height))
}

/** Text input for the focused field, through a method request or the legacy service. */
private class SessionTextInput : PlatformTextInputService {
    private var request: PlatformTextInputMethodRequest? = null
    private var edit: ((List<EditCommand>) -> Unit)? = null
    // The legacy service is told the value and caret; a request is asked for them.
    private var value = TextFieldValue()
    private var caret: LayoutRect? = null
    /** Where the current composition starts in the field, or -1 without one. */
    private var compositionStart = -1
    val active: Boolean get() = request != null || edit != null
    /** The caret in root pixels, as the focused field reports it. */
    val focusedArea: LayoutRect? get() = request?.focusedRectInRoot?.invoke() ?: caret

    fun startRequest(request: PlatformTextInputMethodRequest) { this.request = request; compositionStart = -1 }
    fun endRequest(request: PlatformTextInputMethodRequest) {
        if (this.request === request) { this.request = null; compositionStart = -1 }
    }
    override fun startInput(value: TextFieldValue, imeOptions: ImeOptions, onEditCommand: (List<EditCommand>) -> Unit, onImeActionPerformed: (ImeAction) -> Unit) {
        edit = onEditCommand
        this.value = value
        caret = null
        compositionStart = -1
    }
    override fun stopInput() { edit = null; request = null; caret = null; compositionStart = -1 }
    override fun showSoftwareKeyboard() = Unit
    override fun hideSoftwareKeyboard() = Unit
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) { value = newValue }
    override fun notifyFocusedRect(rect: LayoutRect) { caret = rect }
    fun commit(text: String): Boolean {
        val callback = request?.onEditCommand ?: edit ?: return false
        compositionStart = -1 // Committed text replaces the composition.
        callback(listOf(CommitTextCommand(text, 1)))
        return true
    }
    /** Null removes the composition, and only an existing one: it never deletes a selection. */
    fun setComposing(text: ComposingText?): Boolean {
        val callback = request?.onEditCommand ?: edit ?: return false
        if (text == null) {
            if (compositionStart < 0) return false
            compositionStart = -1
            callback(listOf(SetComposingTextCommand("", 1)))
            return true
        }
        if (compositionStart < 0) {
            // A new composition replaces the selection; later ones replace the composition in place.
            val current = request?.value?.invoke() ?: value
            compositionStart = current.composition?.start ?: current.selection.min
        }
        val cursor = compositionStart + text.cursor
        callback(listOf(SetComposingTextCommand(text.text, 1), SetSelectionCommand(cursor, cursor)))
        return true
    }
}

internal fun UiKey.composeKey(): Key = when (this) {
    UiKey.A -> Key.A; UiKey.B -> Key.B; UiKey.C -> Key.C; UiKey.D -> Key.D; UiKey.E -> Key.E; UiKey.F -> Key.F
    UiKey.G -> Key.G; UiKey.H -> Key.H; UiKey.I -> Key.I; UiKey.J -> Key.J; UiKey.K -> Key.K; UiKey.L -> Key.L
    UiKey.M -> Key.M; UiKey.N -> Key.N; UiKey.O -> Key.O; UiKey.P -> Key.P; UiKey.Q -> Key.Q; UiKey.R -> Key.R
    UiKey.S -> Key.S; UiKey.T -> Key.T; UiKey.U -> Key.U; UiKey.V -> Key.V; UiKey.W -> Key.W; UiKey.X -> Key.X
    UiKey.Y -> Key.Y; UiKey.Z -> Key.Z
    UiKey.DIGIT_0 -> Key.Zero; UiKey.DIGIT_1 -> Key.One; UiKey.DIGIT_2 -> Key.Two; UiKey.DIGIT_3 -> Key.Three; UiKey.DIGIT_4 -> Key.Four
    UiKey.DIGIT_5 -> Key.Five; UiKey.DIGIT_6 -> Key.Six; UiKey.DIGIT_7 -> Key.Seven; UiKey.DIGIT_8 -> Key.Eight; UiKey.DIGIT_9 -> Key.Nine
    UiKey.MINUS -> Key.Minus; UiKey.EQUALS -> Key.Equals; UiKey.LEFT_BRACKET -> Key.LeftBracket; UiKey.RIGHT_BRACKET -> Key.RightBracket
    UiKey.BACKSLASH -> Key.Backslash; UiKey.SEMICOLON -> Key.Semicolon; UiKey.APOSTROPHE -> Key.Apostrophe; UiKey.GRAVE -> Key.Grave
    UiKey.COMMA -> Key.Comma; UiKey.PERIOD -> Key.Period; UiKey.SLASH -> Key.Slash
    UiKey.F1 -> Key.F1; UiKey.F2 -> Key.F2; UiKey.F3 -> Key.F3; UiKey.F4 -> Key.F4; UiKey.F5 -> Key.F5; UiKey.F6 -> Key.F6
    UiKey.F7 -> Key.F7; UiKey.F8 -> Key.F8; UiKey.F9 -> Key.F9; UiKey.F10 -> Key.F10; UiKey.F11 -> Key.F11; UiKey.F12 -> Key.F12
    UiKey.ENTER -> Key.Enter; UiKey.ESCAPE -> Key.Escape; UiKey.TAB -> Key.Tab; UiKey.SPACE -> Key.Spacebar
    UiKey.BACKSPACE -> Key.Backspace; UiKey.DELETE -> Key.Delete; UiKey.INSERT -> Key.Insert
    UiKey.LEFT -> Key.DirectionLeft; UiKey.RIGHT -> Key.DirectionRight; UiKey.UP -> Key.DirectionUp; UiKey.DOWN -> Key.DirectionDown
    UiKey.HOME -> Key.MoveHome; UiKey.END -> Key.MoveEnd; UiKey.PAGE_UP -> Key.PageUp; UiKey.PAGE_DOWN -> Key.PageDown
    UiKey.NUMPAD_0 -> Key.NumPad0; UiKey.NUMPAD_1 -> Key.NumPad1; UiKey.NUMPAD_2 -> Key.NumPad2; UiKey.NUMPAD_3 -> Key.NumPad3
    UiKey.NUMPAD_4 -> Key.NumPad4; UiKey.NUMPAD_5 -> Key.NumPad5; UiKey.NUMPAD_6 -> Key.NumPad6; UiKey.NUMPAD_7 -> Key.NumPad7
    UiKey.NUMPAD_8 -> Key.NumPad8; UiKey.NUMPAD_9 -> Key.NumPad9
    UiKey.NUMPAD_DECIMAL -> Key.NumPadDot; UiKey.NUMPAD_DIVIDE -> Key.NumPadDivide; UiKey.NUMPAD_MULTIPLY -> Key.NumPadMultiply
    UiKey.NUMPAD_SUBTRACT -> Key.NumPadSubtract; UiKey.NUMPAD_ADD -> Key.NumPadAdd; UiKey.NUMPAD_ENTER -> Key.NumPadEnter
    UiKey.NUMPAD_EQUALS -> Key.NumPadEquals
    UiKey.SHIFT_LEFT -> Key.ShiftLeft; UiKey.SHIFT_RIGHT -> Key.ShiftRight; UiKey.CTRL_LEFT -> Key.CtrlLeft; UiKey.CTRL_RIGHT -> Key.CtrlRight
    UiKey.ALT_LEFT -> Key.AltLeft; UiKey.ALT_RIGHT -> Key.AltRight; UiKey.META_LEFT -> Key.MetaLeft; UiKey.META_RIGHT -> Key.MetaRight
    UiKey.CAPS_LOCK -> Key.CapsLock; UiKey.NUM_LOCK -> Key.NumLock; UiKey.SCROLL_LOCK -> Key.ScrollLock
    UiKey.PRINT_SCREEN -> Key.PrintScreen; UiKey.PAUSE -> Key.Break; UiKey.MENU -> Key.Menu
    UiKey.UNKNOWN -> Key.Unknown
}
