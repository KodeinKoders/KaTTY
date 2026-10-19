package app.katty.terminal.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.terminal.KeyInput
import app.katty.terminal.TerminalKey
import app.katty.terminal.TerminalClipboardLocation
import app.katty.terminal.TerminalEvent
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalSelectionAdjust
import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalTextFormat
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
 * @param fontSize The size of the text, which gives the size of a cell.
 * @param font The font, which is monospaced: the bundled JetBrains Mono by default. The cells change with it, and so does the size of the
 * terminal in cells, which is told to the session.
 * Links (an address that a program marked with OSC 8, or one that is written in the text) are opened with Command and a click on
 * macOS, and Control and a click elsewhere, even when the program asked for the mouse: the link is underlined while the modifier is
 * held over it.
 *
 * A terminal that the host does not show should be paused with [TerminalSession.setVisible]: a session produces frames for as long as it
 * is visible, whether or not this is composed.
 *
 * What a program puts in the clipboard (OSC 52) is put there, unless the session was started without allowing it. The other
 * [TerminalEvent]s of the session are the host's.
 *
 * @param focusRequester Gives the focus to the terminal, for whatever else takes it, such as a menu that is
 * closed, to give it back.
 * @param primarySelection What was selected last, which a middle click pastes. Share one between terminals to paste what is selected in
 * one into another.
 * @param onOpenLink Opens the address of a link that was clicked. By default the system opens the ones that start with `http`, `https`,
 * `ftp`, `file` and `mailto`, and the others are refused.
 * @param ligatures Whether the font joins sequences such as `=>` and `!=` into one glyph. Most programmers' fonts do, and it is on by default.
 * @param focusOnStart Whether the terminal takes the keyboard focus when it is first shown. A host that shows several terminals decides which
 * one has it, and turns this off.
 * @param onContextMenu Called with the position of a right press (in pixels, from the top left of the terminal) that is not the program's:
 * the program did not ask for the mouse, or Shift is held. The host shows its menu there. Without it the press is handled as before.
 * @param optionAsAlt Which Option keys are the program's Alt (Option and `b` is `ESC b`, which is how Emacs and the shell's line editor move
 * by word) instead of typing the characters of the layout (`∫`). None by default, as in Ghostty. It cannot be tried without a real keyboard.
 * @param confirmPaste Asked before a paste that could run commands that the user did not see (see [TerminalSession.isPasteUnsafe]): it
 * has the text, and answers whether to paste it. Without it everything is pasted, which is what Ghostty's "never" setting does.
 * @param minimumContrast The least WCAG contrast ratio between a text and its background, from 1 (none, the default) to 21: a text that is
 * less readable is moved towards white or black until it is. The colors of the program are what they are: this is for the ones it did not
 * think of, such as dark blue on black.
 * @param padding The space around the grid, in the background color. The grid is what is left, which is the size the program is given.
 * @param backgroundOpacity How opaque the background is, from 0 to 1: the terminal and its padding, not the backgrounds that a program gave
 * its cells, which stay opaque. Less than 1 only shows something if the window is transparent, which is the host's to do.
 * @param fontFeatures More OpenType features of the font, such as `ss01, zero` (a slashed zero, in JetBrains Mono): the names, with a `0`
 * after one to turn it off, separated by commas, and **no quotes**, which Compose ignores them with. They come after [ligatures].
 * @param builtinGlyphs Whether the box drawing, the blocks, the braille patterns and the arrows of powerline are drawn as shapes that fill
 * their cell (the default), which makes lines and blocks join whatever the font, or with the glyphs of the font.
 * @param bell What a BEL character does: a ring pulses around the cursor, which is small (the default), the system beeps, both, or nothing. The session's
 * [TerminalEvent.Bell] is there for a host that does more, such as marking the tab.
 * @param secureInput When macOS keeps what is typed from the other programs (secure event input), which a key logger would otherwise
 * read: never (the default), while a program reads a password, or whenever the terminal has the keys. It also stops the programs that listen
 * to the keys for the user, such as text expanders, for as long as it is on. It does nothing off macOS.
 * @param copyOnSelect Whether what is selected is put in the clipboard as soon as the selection is made (the mouse is released), as
 * well as being kept for the middle click. Off by default: the clipboard is the user's own.
 * @param copyRichText Whether a copy also offers the selection as HTML, with its styles and colors, for the programs that paste it as
 * such (a mail, a document); the others still get the text.
 * @param optionClickMovesCursor Whether Option and a click on the command line that the shell is reading moves its cursor there, with
 * the arrow keys, as Ghostty does. It needs the shell to mark its input (see `shellIntegration` of [TerminalSession.start]), and
 * it is only the row of the cursor. A click anywhere else is a selection, as it is without it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun Terminal(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    font: TerminalFont = TerminalFont.bundled(),
    focusRequester: FocusRequester = remember { FocusRequester() },
    onOpenLink: (String) -> Unit = ::openInBrowser,
    primarySelection: TerminalPrimarySelection = remember(session) { TerminalPrimarySelection() },
    ligatures: Boolean = true,
    focusOnStart: Boolean = true,
    onContextMenu: ((Offset) -> Unit)? = null,
    optionAsAlt: TerminalOptionAsAlt = TerminalOptionAsAlt.NONE,
    confirmPaste: (suspend (text: String) -> Boolean)? = null,
    minimumContrast: Float = 1f,
    padding: PaddingValues = PaddingValues(0.dp),
    backgroundOpacity: Float = 1f,
    fontFeatures: String = "",
    builtinGlyphs: Boolean = true,
    bell: TerminalBell = TerminalBell.VISUAL,
    secureInput: TerminalSecureInput = TerminalSecureInput.NEVER,
    copyOnSelect: Boolean = false,
    copyRichText: Boolean = false,
    optionClickMovesCursor: Boolean = true,
) {
    require(minimumContrast in 1f..21f) { "The minimum contrast is from 1 (none) to 21, not $minimumContrast" }
    require(backgroundOpacity in 0f..1f) { "The opacity is from 0 to 1, not $backgroundOpacity" }
    val frame by session.frames.collectAsState()
    val typography = rememberTerminalTypography(fontSize, font, ligatures, fontFeatures)

    var size by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    // The composable keeps its focus when the window loses the one of the system: both are needed for the
    // keys to go to the terminal.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val active = focused && windowFocused
    val keys = remember(optionAsAlt) { KeyTranslator(optionAsAlt) }
    // The text that is typed comes from two routes, and what is being composed is not in the terminal yet.
    val textInput = remember(session) {
        TerminalTextInput { session.sendKey(KeyInput(TerminalKey.UNIDENTIFIED, text = it)) }.also { it.onKey = session::sendKey }
    }

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
    // What is pasted by a key and by the middle click: after the host said yes, when what it pastes is what could run commands.
    val latestConfirmPaste = rememberUpdatedState(confirmPaste)
    val pasteConfirmed: (String) -> Unit = remember(session) {
        { text ->
            val confirm = latestConfirmPaste.value
            if (confirm == null || !session.isPasteUnsafe(text)) {
                session.paste(text)
            } else {
                scope.launch { if (confirm(text)) session.paste(text) }
            }
        }
    }
    val copy: (String) -> Unit = remember(clipboard) {
        { text -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
    }
    // What is selected goes to the clipboard, as text and, when asked, as HTML too.
    val latestCopyRichText = rememberUpdatedState(copyRichText)
    val copySelected: () -> Unit = remember(session, clipboard) {
        {
            session.selectedText()?.let { text ->
                val html = if (latestCopyRichText.value) session.selectedText(TerminalTextFormat.HTML) else null
                if (html == null) copy(text) else scope.launch { clipboard.setClipEntry(ClipEntry(RichText(text, html))) }
            }
        }
    }
    val latestCopyOnSelect = rememberUpdatedState(copyOnSelect)
    val latestOptionClick = rememberUpdatedState(optionClickMovesCursor)
    val primary = primarySelection
    val links = remember(session) { LinkInteraction() }
    val contextMenu = remember(session) { ContextMenuGesture() }
    val latestContextMenu = rememberUpdatedState(onContextMenu)
    val openLink = rememberUpdatedState(onOpenLink)
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
    // The session is a key too: a pane shows another terminal's session in the same composable (switching workspace keeps its place, and the
    // grid is the same size), and a session that was not shown has the size it started with, which its program is still drawn for.
    LaunchedEffect(session, cols, rows, typography) {
        if (cols < 1 || rows < 1) return@LaunchedEffect
        // Resizing takes the lock of the terminal: not on the UI thread, which would wait for the program's output.
        withContext(Dispatchers.Default) {
            session.resize(cols, rows, typography.cellWidth.roundToInt(), typography.cellHeight.roundToInt())
        }
    }

    if (focusOnStart) LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // What is typed is kept from the other programs of the system while it has to be: the system counts, so each enable is undone.
    val secure = active && when (secureInput) {
        TerminalSecureInput.NEVER -> false
        TerminalSecureInput.PASSWORD_PROMPTS -> frame.cursor.passwordInput
        TerminalSecureInput.ALWAYS -> true
    }
    DisposableEffect(secure) {
        val enabled = secure && SecureKeyboardEntry.enable()
        onDispose { if (enabled) SecureKeyboardEntry.disable() }
    }

    // The pulse of the bell, from 1 when it rings to 0 when it is over: a ring grows around the cursor.
    val bellPulse = remember { Animatable(0f) }

    // What a program puts in the clipboard (OSC 52): the system's goes there, and what a middle click pastes is kept here. The bell rings.
    LaunchedEffect(session, bell) {
        var lastBeep = 0L
        session.events.collect { event ->
            when (event) {
                is TerminalEvent.ClipboardWrite -> when (event.location) {
                    TerminalClipboardLocation.STANDARD -> copy(event.text)
                    TerminalClipboardLocation.PRIMARY, TerminalClipboardLocation.SELECTION -> primary.text = event.text
                }
                is TerminalEvent.Bell -> {
                    if (bell.visual) launch {
                        bellPulse.snapTo(1f)
                        bellPulse.animateTo(0f, tween(BELL_PULSE_MS))
                    }
                    // A program that rings in a loop does not get a beep for each.
                    val now = System.nanoTime()
                    if (bell.audio && now - lastBeep > BELL_BEEP_INTERVAL_NS) {
                        lastBeep = now
                        java.awt.Toolkit.getDefaultToolkit().beep()
                    }
                }
                else -> Unit
            }
        }
    }

    // The screen moves under a pointer that does not: what it is over may be another link, or none.
    LaunchedEffect(frame) {
        if (links.hovered != null) links.refresh(true, typography.cellWidth, typography.cellHeight) { column, row -> session.linkAt(column, row) }
    }

    // The release of the keys that are down will not be seen.
    LaunchedEffect(active) {
        if (!active) {
            keys.reset()
            selectionModifiers = SelectionModifiers.NONE
        }
    }

    // A program that asked to know (mode 1004) is told when the keys start and stop going to this terminal. The focus moves for a
    // moment when the input method session opens or closes: waiting a little, the effect is restarted by that and nothing is
    // reported, where a program would be told that it lost the focus and had it back.
    LaunchedEffect(session, active) {
        delay(FOCUS_REPORT_DELAY_MS)
        // It takes the lock of the terminal: not on the UI thread, which would wait for the program's output.
        withContext(Dispatchers.Default) { session.setFocused(active) }
    }

    TerminalView(
        frame = frame,
        typography = typography,
        focused = active,
        preedit = textInput.preedit,
        scrollbarAlpha = scrollbarAlpha,
        scrollbarEmphasized = scrollbarInteraction.hovered || scrollbarInteraction.dragging,
        hoveredLink = links.hovered,
        minimumContrast = minimumContrast,
        builtinGlyphs = builtinGlyphs,
        bellPulse = { bellPulse.value },
        // The background is painted here, with the padding, and not by the view, which would paint it over again where it is translucent.
        defaultBackgroundAlpha = 0f,
        modifier = modifier
            .background(frame.background.rgb.toColor().copy(alpha = backgroundOpacity))
            .padding(padding)
            .onSizeChanged { size = it }
            .onGloballyPositioned { coordinates = it }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .pointerHoverIcon(
                terminalPointerIcon(
                    frame.pointerShape, frame.mouseTracking, selectionModifiers,
                    // Only while there is a scrollbar: what was hovered is stale when it is gone.
                    overScrollbar = frame.scrollbar != null && (scrollbarInteraction.hovered || scrollbarInteraction.dragging),
                    overLink = links.hovered != null,
                ),
            )
            .pointerInput(session, focusRequester, primary) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // The terminal takes the focus when it is pressed, and is told about it or not.
                        if (event.type == PointerEventType.Press) focusRequester.requestFocus()

                        if (event.type == PointerEventType.Exit) {
                            scrollbarInteraction.exit()
                            links.exit()
                        }
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

                        // A click on a link, with the modifier that opens them, is the link's: not the program's, and not a selection.
                        if (!selection.selecting) {
                            val ofLink = links.handle(
                                info, typography.cellWidth, typography.cellHeight,
                                linkAt = { column, row -> session.linkAt(column, row) },
                                open = { openLink.value(it) },
                            )
                            if (ofLink) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }

                        // The right button opens the host's menu, when the program did not ask for the mouse or Shift is held.
                        if (latestContextMenu.value != null && !selection.selecting) {
                            val taken = contextMenu.handle(info, tracking) { x, y -> latestContextMenu.value?.invoke(Offset(x, y)) }
                            if (taken) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }

                        // Option and a click on the command line that the shell is reading moves its cursor there: it is not a selection.
                        if (latestOptionClick.value && info.kind == PointerInfo.Kind.PRESS && info.button == TerminalMouseButton.LEFT &&
                            info.alt && !info.meta && !info.shift && !info.ctrl && !tracking && !selection.selecting
                        ) {
                            val column = (info.x / typography.cellWidth).toInt()
                            val row = (info.y / typography.cellHeight).toInt()
                            if (session.moveCursorTo(column, row)) {
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
                                        session.selectionRelease(action.column, action.row)?.let {
                                            primary.text = it
                                            if (latestCopyOnSelect.value) copySelected()
                                        }
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // The middle button pastes what was selected last, without the clipboard.
                        if (middleClickPastes(info, tracking)) {
                            primary.text?.let(pasteConfirmed)
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
                            ClipboardShortcut.COPY -> copySelected()
                            ClipboardShortcut.PASTE -> scope.launch { clipboard.readText()?.let(pasteConfirmed) }
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
                // Shift and an arrow extend what is selected, when something is: nothing is the program's then, since typing unselects.
                selectionAdjustment(info)?.let { adjustment ->
                    if (latestFrame.value.selection.any { it != null }) {
                        if (info.kind == KeyInfo.Kind.PRESSED) session.adjustSelection(adjustment)
                        return@onPreviewKeyEvent true
                    }
                }
                // The modifier of the links was pressed or released: the link under the pointer shows or goes.
                links.refresh(
                    linkModifier(info.shift, info.ctrl, info.alt, info.meta), typography.cellWidth, typography.cellHeight,
                ) { column, row -> session.linkAt(column, row) }
                // The keys that jump between the prompts that the shell marked: when there is history to jump in.
                promptJump(info)?.let { jump ->
                    if (latestFrame.value.scrollbar != null) {
                        if (info.kind == KeyInfo.Kind.PRESSED) {
                            scrolledAt++
                            session.scrollToPrompt(jump)
                        }
                        return@onPreviewKeyEvent true
                    }
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
                    textInput.deliver(text, TextRoute.KEY_TYPED, input)
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

// How long the focus must stay where it is before a program is told.
private const val FOCUS_REPORT_DELAY_MS = 50L

/** What a BEL does. */
public enum class TerminalBell(internal val visual: Boolean, internal val audio: Boolean) {
    /** Nothing. */
    NONE(false, false),

    /** A ring pulses around the cursor, whether or not the program has hidden it. */
    VISUAL(true, false),

    /** The system beeps. */
    AUDIO(false, true),

    /** Both. */
    BOTH(true, true),
}

// How long the ring of the bell takes to grow and fade away.
private const val BELL_PULSE_MS = 400

// The least time between two beeps.
private const val BELL_BEEP_INTERVAL_NS = 200_000_000L
