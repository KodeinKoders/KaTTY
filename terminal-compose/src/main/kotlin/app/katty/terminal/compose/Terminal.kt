package app.katty.terminal.compose

import androidx.compose.foundation.focusable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.katty.terminal.KeyInput
import app.katty.terminal.TerminalKey
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.roundToInt

/**
 * Shows what [session] displays, and sends it what is typed.
 *
 * It takes the focus when it is shown, and when it is clicked. The terminal is resized to fill the space that it is given, with
 * as many cells as fit in it.
 *
 * The cursor shows whether the keys go to the terminal: it is a block that blinks when the program asks for it,
 * and an outline that does not blink when the window, or the terminal, does not have the focus.
 *
 * @param fontSize The size of the text, which gives the size of a cell. The font is always monospace.
 * @param focusRequester Gives the focus to the terminal, for whatever else takes it, such as a menu that is
 * closed, to give it back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun Terminal(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val frame by session.frames.collectAsState()
    val typography = rememberTerminalTypography(fontSize)

    var size by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    // The composable keeps its focus when the window loses the one of the system: both are needed for the
    // keys to go to the terminal.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val active = focused && windowFocused
    val keys = remember { KeyTranslator() }
    // The text that is typed comes from two routes, and what is being composed is not in the terminal yet.
    val textInput = remember(session) { TerminalTextInput { session.sendKey(KeyInput(TerminalKey.UNIDENTIFIED, text = it)) } }

    // What the input method asks for is not composed: the cell of the cursor, for its candidate window.
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestFrame = rememberUpdatedState(frame)
    val latestTypography = rememberUpdatedState(typography)
    val mouse = remember(session) { MouseTranslator() }
    val selection = remember(session) { SelectionDriver() }
    // Which of the modifiers that select are down: Shift when the program has the mouse, and Option with Command for
    // a block. The pointer shows it.
    var selectionModifiers by remember { mutableStateOf(SelectionModifiers.NONE) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = remember(clipboard) {
        { text -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
    }
    val primary = remember(session) { PrimarySelection() }
    val wheel = remember(session) { WheelScroller() }
    val wheelX = remember(session) { WheelNormalizer() }
    val wheelY = remember(session) { WheelNormalizer() }
    val scrollbarInteraction = remember(session) { ScrollbarInteraction() }
    val autoscroll = remember(session, scope) { SelectionAutoscroll(scope, session) }
    val density = LocalDensity.current
    // The scrollbar shows for a moment when the user scrolls, and while the pointer is on it.
    var scrolledAt by remember { mutableStateOf(0) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrolledAt) {
        if (scrolledAt == 0) return@LaunchedEffect
        recentlyScrolled = true
        delay(SCROLLBAR_VISIBLE_MS)
        recentlyScrolled = false
    }
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (frame.scrollbar != null && (recentlyScrolled || scrollbarInteraction.hovered || scrollbarInteraction.dragging)) 1f else 0f,
        animationSpec = tween(SCROLLBAR_FADE_MS),
    )
    val caretRect = remember { { caretRectInRoot(latestFrame.value, latestTypography.value, coordinates) } }

    // As many cells as fit. It is the number of them that matters, not the size in pixels: the terminal is
    // not resized while a window is dragged for as long as the grid is the same.
    val cols = (size.width / typography.cellWidth).toInt()
    val rows = (size.height / typography.cellHeight).toInt()
    LaunchedEffect(cols, rows, typography) {
        if (cols < 1 || rows < 1) return@LaunchedEffect
        // Resizing takes the lock of the terminal: not on the UI thread, which would wait for the program's output.
        withContext(Dispatchers.Default) {
            session.resize(cols, rows, typography.cellWidth.roundToInt(), typography.cellHeight.roundToInt())
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The release of the keys that are down will not be seen.
    LaunchedEffect(active) {
        if (!active) {
            keys.reset()
            selectionModifiers = SelectionModifiers.NONE
        }
    }

    TerminalView(
        frame = frame,
        typography = typography,
        focused = active,
        preedit = textInput.preedit,
        scrollbarAlpha = scrollbarAlpha,
        scrollbarEmphasized = scrollbarInteraction.hovered || scrollbarInteraction.dragging,
        modifier = modifier
            .onSizeChanged { size = it }
            .onGloballyPositioned { coordinates = it }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .pointerHoverIcon(
                terminalPointerIcon(
                    frame.pointerShape, frame.mouseTracking, selectionModifiers,
                    // Only while there is a scrollbar: what was hovered is stale when it is gone.
                    overScrollbar = frame.scrollbar != null && (scrollbarInteraction.hovered || scrollbarInteraction.dragging),
                ),
            )
            .pointerInput(session, focusRequester, primary) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // The terminal takes the focus when it is pressed, and is told about it or not.
                        if (event.type == PointerEventType.Press) focusRequester.requestFocus()

                        if (event.type == PointerEventType.Exit) scrollbarInteraction.exit()
                        val info = event.toPointerInfo(wheelX, wheelY) ?: continue
                        selectionModifiers = SelectionModifiers(info.shift, info.alt, info.meta)
                        val typography = latestTypography.value
                        val tracking = latestFrame.value.mouseTracking

                        // The scrollbar is along the right edge, and what the pointer does there is for it.
                        val position = latestFrame.value.scrollbar
                        if (position != null && !selection.selecting) {
                            val geometry = ScrollbarGeometry(size.height.toFloat(), position, with(density) { SCROLLBAR_MIN_THUMB_HEIGHT.toPx() })
                            val handled = scrollbarInteraction.handle(info, geometry, size.width.toFloat(), with(density) { SCROLLBAR_STRIP_WIDTH.toPx() }) {
                                scrolledAt++
                                session.scrollToRow(it)
                            }
                            if (handled) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }

                        // Selecting text is the terminal's, and the program is told nothing of it.
                        val selecting = selection.translate(info, typography.cellWidth, typography.cellHeight, tracking)
                        if (selecting.isNotEmpty() || selection.selecting) {
                            for (action in selecting) {
                                when (action) {
                                    is SelectionAction.Press -> {
                                        autoscroll.stop()
                                        session.selectionPress(action.column, action.row)
                                    }
                                    // Past the top or the bottom, the history scrolls for as long as the pointer stays.
                                    is SelectionAction.Drag -> autoscroll.drag(action.column, action.row, action.rectangle) { scrolledAt++ }
                                    // What is selected is kept for the middle button. It is the clipboard's only when the user copies.
                                    is SelectionAction.Release -> {
                                        autoscroll.stop()
                                        session.selectionRelease(action.column, action.row)?.let { primary.text = it }
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // The middle button pastes what was selected last, without the clipboard.
                        if (middleClickPastes(info, tracking)) {
                            primary.text?.let(session::paste)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // What the wheel does when the program did not ask for the mouse: the history, or arrow keys.
                        if (info.kind == PointerInfo.Kind.SCROLL && !tracking) {
                            val rows = wheel.add(info.scrollY, maxRows = latestFrame.value.rows.size)
                            if (rows != 0) {
                                scrolledAt++
                                session.scrollWheel(rows)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        if (!tracking) continue
                        val inputs = mouse.translate(info, typography.cellWidth, typography.cellHeight)
                        if (inputs.isEmpty()) continue
                        inputs.forEach(session::sendMouse)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                val info = event.toKeyInfo()
                selectionModifiers = SelectionModifiers(info.shift, info.alt, info.meta)
                // Copying and pasting are the terminal's: Command and C or V on macOS, Control and Shift and C or V elsewhere.
                clipboardShortcut(info)?.let { shortcut ->
                    if (info.kind == KeyInfo.Kind.PRESSED) {
                        when (shortcut) {
                            ClipboardShortcut.COPY -> session.selectedText()?.let(copy)
                            ClipboardShortcut.PASTE -> scope.launch { clipboard.readText()?.let(session::paste) }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // Option and Left or Right, Command and Left, Right or Backspace are the line editor's: see lineEditingText.
                lineEditingText(info)?.let { text ->
                    // A repeat is a press too: holding the keys goes on moving.
                    if (info.kind == KeyInfo.Kind.PRESSED) session.sendText(text)
                    return@onPreviewKeyEvent true
                }
                // Shift and PageUp, PageDown, Home or End scroll the history, when there is some: the program gets nothing. On the
                // alternate screen there is none, and they are the program's.
                if (latestFrame.value.scrollbar != null && isHistoryKey(info)) {
                    if (info.kind == KeyInfo.Kind.PRESSED) {
                        scrolledAt++
                        when (info.keyCode) {
                            AwtKeyEvent.VK_PAGE_UP -> session.scrollPage(-1)
                            AwtKeyEvent.VK_PAGE_DOWN -> session.scrollPage(1)
                            AwtKeyEvent.VK_HOME -> session.scrollToTop()
                            else -> session.scrollToBottom()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (info.kind == KeyInfo.Kind.PRESSED) textInput.keyPressed()
                val input = keys.translate(info) ?: return@onPreviewKeyEvent false
                val text = input.text
                if (info.kind == KeyInfo.Kind.TYPED && text != null) {
                    // The input method may deliver it as well.
                    textInput.deliver(text, TextRoute.KEY_TYPED)
                } else {
                    session.sendKey(input)
                }
                true
            }
            // Compose's focus, not the window's: see terminalTextInput.
            .terminalTextInput(textInput, enabled = focused, caretRectInRoot = caretRect)
            .focusable(),
    )
}

// The cell of the cursor in the window, in pixels.
private fun caretRectInRoot(frame: TerminalFrame, typography: TerminalTypography, coordinates: LayoutCoordinates?): Rect? {
    val x = frame.cursor.x ?: return null
    val y = frame.cursor.y ?: return null
    val origin = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: return null
    return Rect(
        Offset(origin.x + x * typography.cellWidth, origin.y + y * typography.cellHeight),
        Size(typography.cellWidth, typography.cellHeight),
    )
}

private val logger = org.slf4j.LoggerFactory.getLogger("app.katty.terminal.compose.Terminal")

// What matters of a pointer event of the mouse, or `null` for the ones that the terminal is not told about (the pointer
// entering, or leaving with no button down).
@OptIn(ExperimentalComposeUiApi::class)
private fun PointerEvent.toPointerInfo(wheelX: WheelNormalizer, wheelY: WheelNormalizer): PointerInfo? {
    val kind = when (type) {
        PointerEventType.Press -> PointerInfo.Kind.PRESS
        PointerEventType.Release -> PointerInfo.Kind.RELEASE
        PointerEventType.Move -> PointerInfo.Kind.MOVE
        PointerEventType.Scroll -> PointerInfo.Kind.SCROLL
        // The pointer leaves the terminal with a button down, which is a drag that goes on outside: its position is past
        // the edge, which is how a selection autoscrolls.
        PointerEventType.Exit -> if (buttons.isPrimaryPressed) PointerInfo.Kind.MOVE else return null
        else -> return null
    }
    val change = changes.firstOrNull() ?: return null
    val held = buildSet {
        if (buttons.isPrimaryPressed) add(TerminalMouseButton.LEFT)
        if (buttons.isSecondaryPressed) add(TerminalMouseButton.RIGHT)
        if (buttons.isTertiaryPressed) add(TerminalMouseButton.MIDDLE)
        if (buttons.isBackPressed) add(TerminalMouseButton.BACK)
        if (buttons.isForwardPressed) add(TerminalMouseButton.FORWARD)
    }
    return PointerInfo(
        kind = kind,
        x = change.position.x,
        y = change.position.y,
        button = button?.toTerminal(),
        held = held,
        shift = keyboardModifiers.isShiftPressed,
        ctrl = keyboardModifiers.isCtrlPressed,
        alt = keyboardModifiers.isAltPressed,
        meta = keyboardModifiers.isMetaPressed,
        scrollX = if (kind == PointerInfo.Kind.SCROLL) normalized(wheelX, change.scrollDelta.x, change.uptimeMillis) else 0f,
        scrollY = if (kind == PointerInfo.Kind.SCROLL) normalized(wheelY, change.scrollDelta.y, change.uptimeMillis) else 0f,
    )
}

// The amount of a scroll along an axis that has one, with the size of a click that a wheel's has. What the mouse really gives is in the
// log, at the debug level.
private fun PointerEvent.normalized(normalizer: WheelNormalizer, precise: Float, timeMs: Long): Float {
    if (precise == 0f) return 0f
    val rotation = (nativeEvent as? java.awt.event.MouseWheelEvent)?.wheelRotation ?: 0
    val amount = normalizer.normalize(precise, rotation, timeMs)
    logger.debug("Wheel: precise {}, rotation {}: {} notches", precise, rotation, amount)
    return amount
}

private fun PointerButton.toTerminal(): TerminalMouseButton? = when (this) {
    PointerButton.Primary -> TerminalMouseButton.LEFT
    PointerButton.Secondary -> TerminalMouseButton.RIGHT
    PointerButton.Tertiary -> TerminalMouseButton.MIDDLE
    PointerButton.Back -> TerminalMouseButton.BACK
    PointerButton.Forward -> TerminalMouseButton.FORWARD
    else -> null
}
