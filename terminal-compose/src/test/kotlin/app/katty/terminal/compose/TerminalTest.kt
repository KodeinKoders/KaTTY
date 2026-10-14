@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalSession
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIDTH = 640
private const val HEIGHT = 300
private const val TIMEOUT_MS = 10_000L

class TerminalTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val scenes = mutableListOf<ImageComposeScene>()

    @AfterTest
    fun close() {
        scenes.forEach { it.close() }
        sessions.forEach { it.close() }
    }

    // Small, so that the terminal visibly changes size to fit the scene.
    private fun session(script: String, cols: Int = 10, rows: Int = 3) =
        TerminalSession.start(cols, rows, command = listOf("/bin/sh", "-c", script)).also { sessions += it }

    private var typography: TerminalTypography? = null

    private fun show(session: TerminalSession): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            typography = rememberTerminalTypography(androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
            Terminal(session, Modifier.fillMaxSize())
        }.also { scenes += it }

    // The scene is a window that has the focus of the system, unless it is said that it does not.
    private fun show(session: TerminalSession, windowFocused: Boolean): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            typography = rememberTerminalTypography(androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
            CompositionLocalProvider(LocalWindowInfo provides object : WindowInfo { override val isWindowFocused = windowFocused }) {
                Terminal(session, Modifier.fillMaxSize())
            }
        }.also { scenes += it }

    private var clock = 0L

    // Renders until the condition holds: what the composable does about its size and its focus is done by
    // effects, which run in the frames of the scene, and the session answers on its own threads.
    private fun ImageComposeScene.renderUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "Still waiting for $what. The screen:\n" + sessions.joinToString("\n") { s -> s.frames.value.let { f -> f.rows.indices.joinToString("|") { f.text(it) } } }
            }
            render(clock)
            clock += 16_000_000
            Thread.sleep(10)
        }
    }

    // A few frames more, for what is done in an effect and has no outcome to wait for: the terminal takes the focus.
    private fun ImageComposeScene.settle() {
        repeat(5) {
            render(clock)
            clock += 16_000_000
        }
    }

    private fun TerminalFrame.text(row: Int) = rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    // What the window gives the scene for an AWT key event: a typed character is of the type Unknown.
    private fun key(id: Int, keyCode: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED) = KeyEvent(
        key = Key(keyCode),
        type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        },
        codePoint = char.code,
    )

    private fun ImageComposeScene.type(char: Char) {
        val code = AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
        sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code, char))
        sendKeyEvent(key(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, char))
        sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code, char))
    }

    @Test
    fun resizesTheTerminalToTheSpaceItHas() {
        val session = session("sleep 30")
        val scene = show(session)

        scene.renderUntil("the terminal to fit the scene") { typography != null && session.frames.value.cols != 10 }

        val typography = typography!!
        val frame = session.frames.value
        assertEquals((WIDTH / typography.cellWidth).toInt(), frame.cols)
        assertEquals((HEIGHT / typography.cellHeight).toInt(), frame.rows.size)
    }

    /** The color of the pixel at the top left of the screen, where the cursor of a program that wrote nothing is. */
    private fun ImageComposeScene.cursorCell(x: Int, y: Int): Int {
        val image = render(clock)
        clock += 16_000_000
        return Bitmap.makeFromImage(image).getColor(x, y) and 0xFFFFFF
    }

    @Test
    fun clickingTheTerminalGivesItTheFocus() {
        // A steady cursor: it is a filled block when the keys go to the terminal, and an outline when not.
        val session = session("printf '\\033[2 q'; sleep 30", cols = 80, rows = 24)
        var focusManager: FocusManager? = null
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            focusManager = LocalFocusManager.current
            typography = rememberTerminalTypography(androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
            CompositionLocalProvider(LocalWindowInfo provides object : WindowInfo { override val isWindowFocused = true }) {
                Terminal(session, Modifier.fillMaxSize())
            }
        }.also { scenes += it }
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 80 }
        scene.settle()
        val cursor = session.frames.value.foreground.rgb
        val background = session.frames.value.background.rgb
        val middle = (typography!!.cellWidth / 2).toInt() to (typography!!.cellHeight / 2).toInt()
        scene.renderUntil("the cursor to be a filled block") { scene.cursorCell(middle.first, middle.second) == cursor }

        focusManager!!.clearFocus()
        scene.renderUntil("the cursor to be an outline") { scene.cursorCell(middle.first, middle.second) == background }

        scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 100f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 100f))
        scene.renderUntil("the cursor to be a filled block again") { scene.cursorCell(middle.first, middle.second) == cursor }
    }

    @Test
    fun theCursorIsABlockThatBlinksWhileTheWindowHasTheFocus() {
        val session = session("sleep 30", cols = 80, rows = 24)
        val scene = show(session, windowFocused = true)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 80 }
        scene.settle()
        val cursor = session.frames.value.foreground.rgb
        val background = session.frames.value.background.rgb
        val middle = (typography!!.cellWidth / 2).toInt() to (typography!!.cellHeight / 2).toInt()

        scene.renderUntil("the cursor to be shown, filled") { scene.cursorCell(middle.first, middle.second) == cursor }
        scene.renderUntil("the cursor to be hidden") { scene.cursorCell(middle.first, middle.second) == background }
        scene.renderUntil("the cursor to be shown again") { scene.cursorCell(middle.first, middle.second) == cursor }
    }

    @Test
    fun theCursorIsAnOutlineThatDoesNotBlinkWhenTheWindowHasLostTheFocus() {
        val session = session("sleep 30", cols = 80, rows = 24)
        val scene = show(session, windowFocused = false)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 80 }
        scene.settle()
        val cursor = session.frames.value.foreground.rgb
        val background = session.frames.value.background.rgb
        val middle = (typography!!.cellWidth / 2).toInt() to (typography!!.cellHeight / 2).toInt()
        val edge = 0 to (typography!!.cellHeight / 2).toInt()

        // Longer than two periods of a blinking cursor: it is the same at every moment.
        val deadline = System.nanoTime() + 1_300_000_000L
        var samples = 0
        while (System.nanoTime() < deadline) {
            assertEquals(background, scene.cursorCell(middle.first, middle.second), "The cursor is not filled")
            assertEquals(cursor, scene.cursorCell(edge.first, edge.second), "Its outline is always drawn")
            samples++
            Thread.sleep(40)
        }
        assertTrue(samples > 10)
    }

    @Test
    fun sendsWhatIsTypedToTheProgram() {
        // cat leaves the terminal in its usual mode, where what is typed is echoed.
        val session = session("cat")
        val scene = show(session)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 10 }
        scene.settle()

        "hi there".forEach { scene.type(it) }

        scene.renderUntil("what was typed to be echoed") { session.frames.value.text(0) == "hi there" }
    }

    @Test
    fun sendsKeysThatHaveNoTextAsKeys() {
        // In raw mode, the program gets exactly the bytes that are sent: ESC [ A is the up arrow.
        val session = session("stty raw -echo; echo ready; head -c3 | od -An -tx1")
        val scene = show(session)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 10 }
        scene.renderUntil("the program to be ready") { session.frames.value.rows.indices.any { session.frames.value.text(it) == "ready" } }
        scene.settle()

        scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_UP))

        scene.renderUntil("the bytes of the arrow") {
            val frame = session.frames.value
            frame.rows.indices.any { frame.text(it).replace(Regex("\\s+"), " ").trim() == "1b 5b 41" }
        }
    }

    @Test
    fun drawsWhatTheProgramWrites() {
        val session = session("printf 'hello \\033[1;32mgreen\\033[0m \\033[44mon blue\\033[0m\\r\\n\\033[4munderlined\\033[0m\\r\\n> '; sleep 30", cols = 80, rows = 24)
        val scene = show(session)
        scene.renderUntil("the output to be drawn") { session.frames.value.text(1) == "underlined" && session.frames.value.text(2) == ">" }
        scene.settle()

        val image = scene.render(clock)
        File("build/renders").apply { mkdirs() }.resolve("session.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)

        val typography = typography!!
        val bitmap = Bitmap.makeFromImage(image)
        // The cell of "on blue", in the color of the terminal's blue: blue is what dominates it.
        val blue = bitmap.getColor((15.5f * typography.cellWidth).toInt(), 2) and 0xFFFFFF
        assertTrue((blue and 0xFF) > (blue shr 16 and 0xFF) + 60, "The background of 'on blue' is blue, it is ${"%06x".format(blue)}")
    }

    // A program in raw mode that asks for the mouse, then prints what it reads without the escapes, so that what is
    // left is text, in a terminal that fills the scene.
    private fun mouseScene(modes: String, bytes: Int): Pair<ImageComposeScene, TerminalSession> {
        val session = session("""printf '$modes'; stty raw -echo; echo ready; head -c$bytes | tr -d '\033' | od -An -c""", cols = 80, rows = 24)
        val scene = show(session, windowFocused = true)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 80 && "ready" in session.frames.value.text(0) }
        scene.renderUntil("the mouse to be asked for") { session.frames.value.mouseTracking }
        scene.settle()
        return scene to session
    }

    private val primary get() = androidx.compose.ui.input.pointer.PointerButton.Primary

    private fun ImageComposeScene.click(position: Offset, shift: Boolean = false) {
        val modifiers = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = shift)
        sendPointerEvent(
            PointerEventType.Press, position, keyboardModifiers = modifiers, button = primary,
            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true),
        )
        sendPointerEvent(
            PointerEventType.Release, position, keyboardModifiers = modifiers, button = primary,
            buttons = androidx.compose.ui.input.pointer.PointerButtons(),
        )
    }

    private fun TerminalSession.awaitReports(scene: ImageComposeScene, expected: String) {
        scene.renderUntil("the program to have read $expected") { expected in frames.value.rows.indices.joinToString("") { frames.value.text(it) }.replace(Regex("\\s+"), "") }
    }

    // The position, in pixels, of the middle of a cell.
    private fun cellCenter(col: Int, row: Int) = Offset((col + 0.5f) * typography!!.cellWidth, (row + 0.5f) * typography!!.cellHeight)

    @Test
    fun reportsAClickAtTheCellUnderThePointerEvenAtTheLastColumn() {
        // The reports are ESC [ < 0 ; col ; row M, then m: 9 bytes with one digit for the column and 10 with two.
        val (scene, session) = mouseScene("\\033[?1000h\\033[?1006h", bytes = 2 * 10)
        val cols = session.frames.value.cols

        // The last column, where the size of a cell that is rounded would be a few cells off.
        scene.click(cellCenter(cols - 1, 1))

        session.awaitReports(scene, "[<0;$cols;2M[<0;$cols;2m")
    }

    @Test
    fun shiftSwallowsAClickWithItsRelease() {
        val (scene, session) = mouseScene("\\033[?1000h\\033[?1006h", bytes = 2 * 9)

        scene.click(cellCenter(4, 2), shift = true)
        scene.click(cellCenter(0, 0))

        // Only the click without Shift is read: it is the first thing that the program got.
        session.awaitReports(scene, "[<0;1;1M[<0;1;1m")
        assertTrue(";5;3" !in session.frames.value.rows.indices.joinToString("") { session.frames.value.text(it) }.replace(Regex("\\s+"), ""))
    }

    @Test
    fun reportsTheWheelAtTheCellUnderThePointer() {
        val (scene, session) = mouseScene("\\033[?1000h\\033[?1006h", bytes = 2 * 10)

        scene.sendPointerEvent(PointerEventType.Scroll, cellCenter(2, 1), scrollDelta = Offset(0f, 1f))
        scene.sendPointerEvent(PointerEventType.Scroll, cellCenter(2, 1), scrollDelta = Offset(0f, -1f))

        session.awaitReports(scene, "[<65;3;2M[<64;3;2M")
    }

    @Test
    fun reportsHoveringOnlyToAProgramThatAskedForAllMotion() {
        val (scene, session) = mouseScene("\\033[?1003h\\033[?1006h", bytes = 20)

        // The first event of a pointer that comes in is its entering, which is not told: the ones after it are.
        scene.sendPointerEvent(PointerEventType.Move, cellCenter(5, 3))
        scene.sendPointerEvent(PointerEventType.Move, cellCenter(6, 3))
        scene.sendPointerEvent(PointerEventType.Move, cellCenter(7, 3))

        session.awaitReports(scene, "[<35;7;4M[<35;8;4M")
    }

    @Test
    fun doesNotReportHoveringToAProgramThatDidNotAskForIt() {
        val (scene, session) = mouseScene("\\033[?1000h\\033[?1006h", bytes = 9)

        scene.sendPointerEvent(PointerEventType.Move, cellCenter(5, 3))
        scene.sendPointerEvent(PointerEventType.Move, cellCenter(6, 3))
        scene.click(cellCenter(0, 0))

        // The click is the first thing that was read.
        session.awaitReports(scene, "[<0;1;1M")
    }

    @Test
    fun sendsNothingToAProgramThatDidNotAskForTheMouse() {
        val session = session("stty raw -echo; echo ready; head -c1 | od -An -c", cols = 80, rows = 24)
        val scene = show(session, windowFocused = true)
        scene.renderUntil("the terminal to fit the scene") { session.frames.value.cols != 80 && "ready" in session.frames.value.text(0) }
        scene.settle()

        scene.click(cellCenter(3, 2))
        scene.sendPointerEvent(PointerEventType.Scroll, cellCenter(2, 1), scrollDelta = Offset(0f, 1f))
        session.sendKey(app.katty.terminal.KeyInput(app.katty.terminal.TerminalKey.ENTER))

        // The first byte that the program read is the Enter.
        scene.renderUntil("the Enter to be read") { "\\r" in session.frames.value.rows.indices.joinToString("") { session.frames.value.text(it) } }
    }

    private class FakeClipboard : androidx.compose.ui.platform.Clipboard {
        val copied = java.util.concurrent.CopyOnWriteArrayList<String>()

        override suspend fun getClipEntry(): androidx.compose.ui.platform.ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
            val transferable = clipEntry?.nativeClipEntry as java.awt.datatransfer.Transferable
            copied += transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
        }
    }

    private val clipboard = FakeClipboard()

    // A terminal that fills the scene, with what the program printed on it, and a clipboard that is not the system's.
    private fun selectionScene(script: String): Pair<ImageComposeScene, TerminalSession> {
        val session = session(script, cols = 80, rows = 24)
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            typography = rememberTerminalTypography(androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
            CompositionLocalProvider(
                LocalWindowInfo provides object : WindowInfo { override val isWindowFocused = true },
                androidx.compose.ui.platform.LocalClipboard provides clipboard,
            ) {
                Terminal(session, Modifier.fillMaxSize())
            }
        }.also { scenes += it }
        scene.renderUntil("the terminal to fit the scene and show the text") { session.frames.value.cols != 80 && session.frames.value.text(0).startsWith("hello world") }
        scene.settle()
        return scene to session
    }

    // A position in cells, in pixels.
    private fun at(column: Float, row: Float) = Offset(column * typography!!.cellWidth, row * typography!!.cellHeight)

    private fun ImageComposeScene.drag(
        from: Offset, to: Offset,
        shift: Boolean = false, alt: Boolean = false, meta: Boolean = false,
    ) {
        val modifiers = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = shift, isAltPressed = alt, isMetaPressed = meta)
        val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Move, from, keyboardModifiers = modifiers)
        sendPointerEvent(PointerEventType.Press, from, keyboardModifiers = modifiers, button = primary, buttons = down)
        sendPointerEvent(PointerEventType.Move, to, keyboardModifiers = modifiers, buttons = down)
        sendPointerEvent(PointerEventType.Release, to, keyboardModifiers = modifiers, button = primary, buttons = androidx.compose.ui.input.pointer.PointerButtons())
    }

    private fun ImageComposeScene.awaitCopied(expected: String) =
        renderUntil("$expected to be copied") { expected in clipboard.copied }

    private fun TerminalSession.selected() = frames.value.selection

    @Test
    fun selectsWhatIsDraggedOverAndCopiesItWhenTheButtonIsUp() {
        val (scene, session) = selectionScene("printf 'hello world'; sleep 30")

        scene.drag(at(0.2f, 0.5f), at(4.7f, 0.5f))

        scene.awaitCopied("hello")
        scene.renderUntil("the selection to be shown") { session.selected().isNotEmpty() }
        assertEquals(0..4, session.selected()[0])
    }

    @Test
    fun selectsAWordWithADoubleClick() {
        val (scene, session) = selectionScene("printf 'hello world'; sleep 30")
        val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)

        repeat(2) {
            scene.sendPointerEvent(PointerEventType.Press, at(7.5f, 0.5f), button = primary, buttons = down)
            scene.sendPointerEvent(PointerEventType.Release, at(7.5f, 0.5f), button = primary, buttons = androidx.compose.ui.input.pointer.PointerButtons())
        }

        scene.awaitCopied("world")
        assertEquals(6..10, session.selected().let { scene.renderUntil("the selection to be shown") { session.selected().isNotEmpty() }; session.selected()[0] })
    }

    @Test
    fun aClickUnselectsAndCopiesNothing() {
        val (scene, session) = selectionScene("printf 'hello world'; sleep 30")
        scene.drag(at(0.2f, 0.5f), at(4.7f, 0.5f))
        scene.awaitCopied("hello")
        val copies = clipboard.copied.size

        scene.click(at(2.5f, 2.5f))

        scene.renderUntil("the selection to go") { session.selected().isEmpty() }
        assertEquals(copies, clipboard.copied.size)
    }

    @Test
    fun optionAndCommandSelectABlock() {
        val (scene, session) = selectionScene("printf 'hello world\\r\\nsecond line'; sleep 30")

        scene.drag(at(2.2f, 0.5f), at(4.7f, 1.5f), alt = true, meta = true)

        scene.awaitCopied("llo\ncon")
    }

    @Test
    fun aProgramThatHasTheMouseKeepsItsClicksAndShiftSelects() {
        val (scene, session) = selectionScene(
            """printf '\033[?1000h\033[?1006h'; printf 'hello world'; stty raw -echo; head -c10 | tr -d '\033' | od -An -c""",
        )
        scene.renderUntil("the mouse to be asked for") { session.frames.value.mouseTracking }

        // Without Shift the program gets the drag, and nothing is selected.
        scene.drag(at(0.2f, 5.5f), at(4.7f, 5.5f))
        session.awaitReports(scene, "[<0;1;6M")
        assertEquals(emptyList(), session.selected())
        assertEquals(emptyList(), clipboard.copied.toList())

        // With Shift the terminal selects, and copies.
        scene.drag(at(0.2f, 0.5f), at(4.7f, 0.5f), shift = true)
        scene.awaitCopied("hello")
        assertEquals(0..4, session.selected().let { scene.renderUntil("the selection to be shown") { session.selected().isNotEmpty() }; session.selected()[0] })
    }

    @Test
    fun theProgramIsToldNothingOfWhatIsSelectedWithShift() {
        val (scene, session) = selectionScene(
            """printf '\033[?1000h\033[?1006h'; printf 'hello world'; stty raw -echo; head -c1 | od -An -c""",
        )
        scene.renderUntil("the mouse to be asked for") { session.frames.value.mouseTracking }

        scene.drag(at(0.2f, 0.5f), at(4.7f, 0.5f), shift = true)
        scene.awaitCopied("hello")
        session.sendKey(app.katty.terminal.KeyInput(app.katty.terminal.TerminalKey.ENTER))

        // The first byte that the program read is the Enter: it was not told about the selection.
        scene.renderUntil("the Enter to be read") { "\\r" in session.frames.value.rows.indices.joinToString("") { session.frames.value.text(it) } }
    }
}
