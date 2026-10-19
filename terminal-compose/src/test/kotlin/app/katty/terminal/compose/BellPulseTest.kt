package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BellPulseTest {
    private val cellHeight = 20f

    @Test
    fun theRingStartsJustOutsideTheCursorsCellAtItsMostOpaque() {
        val ring = bellRing(1f, left = 40f, top = 60f, width = 10f, cellHeight = cellHeight)!!

        // 3 pixels outside on every side, so that it is seen from the first frame.
        assertEquals(37f, ring.left, 0.01f)
        assertEquals(57f, ring.top, 0.01f)
        assertEquals(16f, ring.width, 0.01f)
        assertEquals(26f, ring.height, 0.01f)
        assertEquals(0.7f, ring.alpha)
    }

    @Test
    fun itGrowsOutwardOnEverySideAsItFades() {
        val start = bellRing(1f, 40f, 60f, 10f, cellHeight)!!
        val middle = bellRing(0.5f, 40f, 60f, 10f, cellHeight)!!
        val nearTheEnd = bellRing(0.1f, 40f, 60f, 10f, cellHeight)!!

        assertTrue(middle.left < start.left && middle.top < start.top)
        assertTrue(middle.left + middle.width > start.left + start.width && middle.top + middle.height > start.top + start.height)
        assertTrue(nearTheEnd.width > middle.width && nearTheEnd.height > middle.height)
        assertTrue(start.alpha > middle.alpha && middle.alpha > nearTheEnd.alpha && nearTheEnd.alpha > 0f)
        // About one cell outward at the end, and the corners round as it grows.
        assertEquals(cellHeight * 0.765f, start.top - nearTheEnd.top, 0.01f)
        assertTrue(nearTheEnd.radius > start.radius)
    }

    @Test
    fun itIsCenteredOnTheCursor() {
        val ring = bellRing(0.4f, 40f, 60f, 10f, cellHeight)!!

        assertEquals(40f + 10f / 2, ring.left + ring.width / 2, 0.01f)
        assertEquals(60f + cellHeight / 2, ring.top + ring.height / 2, 0.01f)
    }

    @Test
    fun itIsThinAndThereIsNothingOnceThePulseIsOver() {
        val ring = bellRing(0.7f, 0f, 0f, 10f, cellHeight)!!

        assertTrue(ring.stroke in 1f..3f, "A thin line: ${ring.stroke}")
        assertNull(bellRing(0f, 0f, 0f, 10f, cellHeight))
        assertNull(bellRing(-0.2f, 0f, 0f, 10f, cellHeight))
        assertNotNull(bellRing(2f, 0f, 0f, 10f, cellHeight), "A progress past the start is the start")
    }
}
