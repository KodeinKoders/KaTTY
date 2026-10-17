@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import app.katty.core.WindowFile
import app.katty.core.WindowGeometry
import app.katty.core.WindowMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepWindowGeometryTest {
    private val directory: Path = Files.createTempDirectory("katty-keep-window")
    private val path = directory.resolve("board-window.json")
    private val file = WindowFile(path, WindowGeometry(width = 1100f, height = 720f))

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun awaitFile(what: String, condition: (WindowGeometry) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && condition(file.load())) return
            Thread.sleep(20)
        }
        assertTrue(Files.exists(path) && condition(file.load()), what)
    }

    @Test
    fun theGeometryIsWrittenAfterTheWindowHasStayedWhereItIsAndANewWindowOpensThere() = runComposeUiTest {
        val state = WindowState(position = WindowPosition(10.dp, 20.dp), size = DpSize(1100.dp, 720.dp))
        setContent { keepWindowGeometry(state, file, file.load()) }
        mainClock.advanceTimeBy(600)
        // Nothing changed: nothing is written.
        Thread.sleep(100)
        assertFalse(Files.exists(path))

        state.position = WindowPosition(300.dp, 200.dp)
        state.size = DpSize(900.dp, 600.dp)
        mainClock.advanceTimeBy(600)

        awaitFile("the new place and size") { it.x == 300f && it.y == 200f && it.width == 900f && it.height == 600f }
        // A window opened from what was written is where the last one was.
        val reopened = file.load()
        assertEquals(WindowPosition(300.dp, 200.dp), reopened.toPosition())
        assertEquals(DpSize(900.dp, 600.dp), reopened.toSize())
        assertEquals(WindowPlacement.Floating, reopened.mode.toPlacement())
    }

    @Test
    fun aMaximizedWindowKeepsTheFloatingBoundsAndItsMode() = runComposeUiTest {
        val state = WindowState(position = WindowPosition(40.dp, 50.dp), size = DpSize(1000.dp, 700.dp))
        setContent { keepWindowGeometry(state, file, file.load()) }
        state.size = DpSize(1000.dp, 700.dp)
        mainClock.advanceTimeBy(600)

        // Maximizing reports the size of the screen: what is kept is what it goes back to.
        state.placement = WindowPlacement.Maximized
        state.size = DpSize(1920.dp, 1080.dp)
        mainClock.advanceTimeBy(600)

        awaitFile("the mode, with the floating bounds") { it.mode == WindowMode.MAXIMIZED && it.width != 1920f }
    }

    @Test
    fun flushingWritesAtOnceAndLeavingTheCompositionWritesTheLastState() = runComposeUiTest {
        val state = WindowState(position = WindowPosition(1.dp, 2.dp), size = DpSize(800.dp, 500.dp))
        var shown by mutableStateOf(true)
        var flush: () -> Unit = {}
        setContent { if (shown) flush = keepWindowGeometry(state, file, file.load()) }

        flush()
        assertEquals(800f, file.load().width)

        // The window is moved and goes away before the delay: the last place is written all the same.
        state.position = WindowPosition(77.dp, 88.dp)
        shown = false
        waitForIdle()
        assertEquals(77f, file.load().x)
        assertEquals(88f, file.load().y)
    }
}
