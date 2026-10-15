package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowGeometryTest {
    private val screen = ScreenBounds(0f, 0f, 1920f, 1080f)
    private val second = ScreenBounds(1920f, 0f, 1280f, 800f)

    @Test
    fun aWindowOnAScreenIsKept() {
        val geometry = WindowGeometry(x = 100f, y = 50f, width = 1000f, height = 700f)

        assertEquals(geometry, geometry.sanitized(listOf(screen)))
    }

    @Test
    fun aWindowOnAScreenThatIsGoneIsPutWhereThePlatformPutsItAndKeepsItsSize() {
        val geometry = WindowGeometry(x = 2100f, y = 50f, width = 1000f, height = 700f)

        val sanitized = geometry.sanitized(listOf(screen))

        assertNull(sanitized.x)
        assertNull(sanitized.y)
        assertEquals(1000f, sanitized.width)
        assertEquals(700f, sanitized.height)
    }

    @Test
    fun aWindowOnTheSecondScreenIsKeptWhileItIsThere() {
        val geometry = WindowGeometry(x = 2100f, y = 50f, width = 1000f, height = 700f)

        assertEquals(geometry, geometry.sanitized(listOf(screen, second)))
    }

    @Test
    fun aWindowThatIsMostlyOffAScreenIsKeptWhenEnoughOfItCanBeGrabbed() {
        // 150 dp of it is on the screen, which is more than the 120 that it takes to grab it.
        assertEquals(1770f, WindowGeometry(x = 1770f, y = 10f, width = 1000f, height = 700f).sanitized(listOf(screen)).x)
        // 100 is not.
        assertNull(WindowGeometry(x = 1820f, y = 10f, width = 1000f, height = 700f).sanitized(listOf(screen)).x)
        // The title bar is what is above the screen.
        assertNull(WindowGeometry(x = 100f, y = 1050f, width = 1000f, height = 700f).sanitized(listOf(screen)).y)
    }

    @Test
    fun aSizeUnderTheMinimumIsRaisedToIt() {
        val sanitized = WindowGeometry(x = 0f, y = 0f, width = 100f, height = 50f).sanitized(listOf(screen))

        assertEquals(WindowGeometry.MIN_WIDTH, sanitized.width)
        assertEquals(WindowGeometry.MIN_HEIGHT, sanitized.height)
    }

    @Test
    fun theModeIsKept() {
        assertEquals(WindowMode.FULLSCREEN, WindowGeometry(mode = WindowMode.FULLSCREEN).sanitized(listOf(screen)).mode)
    }
}
