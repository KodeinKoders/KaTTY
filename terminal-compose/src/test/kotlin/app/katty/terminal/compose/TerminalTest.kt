@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
}
