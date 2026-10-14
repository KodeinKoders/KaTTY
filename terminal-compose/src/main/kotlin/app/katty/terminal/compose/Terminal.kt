package app.katty.terminal.compose

import androidx.compose.foundation.focusable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
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
        modifier = modifier
            .onSizeChanged { size = it }
            .onGloballyPositioned { coordinates = it }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .pointerHoverIcon(terminalPointerIcon(frame.pointerShape, frame.mouseTracking, selectionModifiers))
            .pointerInput(session, focusRequester, copy) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // The terminal takes the focus when it is pressed, and is told about it or not.
                        if (event.type == PointerEventType.Press) focusRequester.requestFocus()

                        val info = event.toPointerInfo() ?: continue
                        selectionModifiers = SelectionModifiers(info.shift, info.alt, info.meta)
                        val typography = latestTypography.value
                        val tracking = latestFrame.value.mouseTracking

                        // Selecting text is the terminal's, and the program is told nothing of it.
                        val selecting = selection.translate(info, typography.cellWidth, typography.cellHeight, tracking)
                        if (selecting.isNotEmpty() || selection.selecting) {
                            for (action in selecting) {
                                when (action) {
                                    is SelectionAction.Press -> session.selectionPress(action.column, action.row)
                                    is SelectionAction.Drag -> session.selectionDrag(action.column, action.row, action.rectangle)
                                    // What is selected is copied as soon as the button is up.
                                    is SelectionAction.Release -> session.selectionRelease(action.column, action.row)?.let(copy)
                                }
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

// What matters of a pointer event of the mouse, or `null` for the ones that the terminal is not told about (the pointer
// entering or leaving).
@OptIn(ExperimentalComposeUiApi::class)
private fun PointerEvent.toPointerInfo(): PointerInfo? {
    val kind = when (type) {
        PointerEventType.Press -> PointerInfo.Kind.PRESS
        PointerEventType.Release -> PointerInfo.Kind.RELEASE
        PointerEventType.Move -> PointerInfo.Kind.MOVE
        PointerEventType.Scroll -> PointerInfo.Kind.SCROLL
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
        scrollX = change.scrollDelta.x,
        scrollY = change.scrollDelta.y,
    )
}

private fun PointerButton.toTerminal(): TerminalMouseButton? = when (this) {
    PointerButton.Primary -> TerminalMouseButton.LEFT
    PointerButton.Secondary -> TerminalMouseButton.RIGHT
    PointerButton.Tertiary -> TerminalMouseButton.MIDDLE
    PointerButton.Back -> TerminalMouseButton.BACK
    PointerButton.Forward -> TerminalMouseButton.FORWARD
    else -> null
}
