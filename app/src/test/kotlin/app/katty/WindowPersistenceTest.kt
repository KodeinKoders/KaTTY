package app.katty

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import app.katty.core.WindowGeometry
import app.katty.core.WindowMode
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPersistenceTest {
    private val before = WindowGeometry(x = 10f, y = 20f, width = 1000f, height = 700f)

    @Test
    fun aFloatingWindowKeepsItsPlaceAndSize() {
        val now = geometryOf(WindowPlacement.Floating, false, WindowPosition(300.dp, 200.dp), DpSize(900.dp, 600.dp), before)

        assertEquals(WindowGeometry(x = 300f, y = 200f, width = 900f, height = 600f, mode = WindowMode.FLOATING), now)
    }

    @Test
    fun aMaximizedWindowKeepsTheBoundsItWillGoBackToAndItsMode() {
        // It reports the size of the screen: what is kept is what it was before.
        val now = geometryOf(WindowPlacement.Maximized, false, WindowPosition(0.dp, 0.dp), DpSize(1920.dp, 1080.dp), before)

        assertEquals(before.copy(mode = WindowMode.MAXIMIZED), now)
        assertEquals(before.copy(mode = WindowMode.FULLSCREEN), geometryOf(WindowPlacement.Fullscreen, false, WindowPosition(0.dp, 0.dp), DpSize(1920.dp, 1080.dp), before))
    }

    @Test
    fun aWindowThatIsMinimizedChangesNothing() {
        assertEquals(before, geometryOf(WindowPlacement.Floating, true, WindowPosition(-32000.dp, -32000.dp), DpSize(160.dp, 28.dp), before))
    }

    @Test
    fun aPositionThatTheWindowDoesNotHaveYetIsNotWritten() {
        val now = geometryOf(WindowPlacement.Floating, false, WindowPosition.PlatformDefault, DpSize(900.dp, 600.dp), before)

        assertEquals(10f, now.x)
        assertEquals(20f, now.y)
        assertEquals(900f, now.width)
    }

    @Test
    fun theGeometryBecomesTheStateOfTheWindowAndBack() {
        val geometry = WindowGeometry(x = 40f, y = 60f, width = 1100f, height = 800f, mode = WindowMode.MAXIMIZED)

        assertEquals(WindowPlacement.Maximized, geometry.mode.toPlacement())
        assertEquals(WindowPosition(40.dp, 60.dp), geometry.toPosition())
        assertEquals(DpSize(1100.dp, 800.dp), geometry.toSize())
        assertEquals(WindowPosition.PlatformDefault, WindowGeometry().toPosition())
    }
}
