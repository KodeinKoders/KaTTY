package app.katty

import app.katty.core.TerminalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindTargetTest {
    private val a = TerminalId("a")
    private val b = TerminalId("b")

    @Test
    fun theTerminalWhoseFindBarIsOpenIsStepped() {
        assertEquals(a, findTarget(a, shown = setOf(a, b), running = setOf(a, b)))
    }

    @Test
    fun aFindBarThatIsNotOnTheScreenIsNone() {
        // Another workspace is shown, or the board, which shows no terminal.
        assertNull(findTarget(a, shown = setOf(b), running = setOf(a, b)))
        assertNull(findTarget(a, shown = emptySet(), running = setOf(a, b)))
        // The terminal was closed.
        assertNull(findTarget(a, shown = setOf(a), running = setOf(b)))
        assertNull(findTarget(null, shown = setOf(a), running = setOf(a)))
    }
}
