package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionLookupsTest {
    private var now = 0L
    private val lookups = SessionLookups(keepMs = 5_000, clock = { now })
    private var looks = 0
    private var answer: String? = "a"

    private fun ask(pid: Int = 10, agent: Agent = Agent.CLAUDE, startedAt: Long = 1_000, reported: String? = null) =
        lookups.sessionOf(agent, pid, startedAt, reported) { looks++; answer }

    @Test
    fun aSessionThatWasFoundIsKeptForAWhile() {
        assertEquals("a", ask())
        answer = "b"
        now = 4_999

        assertEquals("a", ask())
        assertEquals(1, looks)
    }

    @Test
    fun itIsLookedForAgainOnceItIsOld() {
        ask()
        answer = "b"
        now = 5_000

        assertEquals("b", ask())
        assertEquals(2, looks)
    }

    @Test
    fun noSessionIsKeptAsWellAsOne() {
        answer = null
        assertNull(ask())
        answer = "a"
        now = 1_000

        assertNull(ask(), "Nothing found is an answer too, for the same time")
        assertEquals(1, looks)
    }

    @Test
    fun anotherProgramIsLookedForAtOnce() {
        ask()
        answer = "b"

        assertEquals("b", ask(pid = 11), "Another pid")
        answer = "c"
        assertEquals("c", ask(pid = 11, startedAt = 2_000), "The same pid, started again")
        answer = "d"
        assertEquals("d", ask(pid = 11, startedAt = 2_000, agent = Agent.OPENCODE), "Another agent")
        assertEquals(4, looks)
    }

    @Test
    fun aSessionThatTheProgramReportsIsLookedForAtOnce() {
        ask(agent = Agent.OPENCODE)
        answer = "ses_b"

        assertEquals("ses_b", ask(agent = Agent.OPENCODE, reported = "ses_b"))
        assertEquals(2, looks)
    }
}
