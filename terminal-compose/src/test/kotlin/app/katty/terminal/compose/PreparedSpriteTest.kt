package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PreparedSpriteTest {
    // A powerline arrow (a polygon) and a rounded corner (a stroke), in a cell of 8 × 16 that starts at 40.
    private val arrow = PreparedSprite(40f, 8f, sprite(0xE0B0, 8, 16)!!, 0xFFFFFF)
    private val corner = PreparedSprite(40f, 8f, sprite(0x256D, 8, 16)!!, 0xFFFFFF)

    @Test
    fun itsPathsAreMadeOnceAndNotAtEachDrawing() {
        assertSame(arrow.polygonPaths, arrow.polygonPaths)
        assertSame(corner.strokePaths, corner.strokePaths)
        assertEquals(1, arrow.polygonPaths.size)
        assertEquals(corner.sprite.strokes.size, corner.strokePaths.size)
    }

    @Test
    fun itsPathsAreWhereItsCellIsOnItsRow() {
        val bounds = arrow.polygonPaths.single().getBounds()

        assertEquals(40f, bounds.left)
        assertEquals(0f, bounds.top)
        assertEquals(48f, bounds.right)
        assertEquals(16f, bounds.bottom)
    }
}
