package app.katty.core

import app.katty.terminal.TerminalCommandOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttentionTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun info(state: AgentState, since: Long, app: String = "claude-code") = AgentInfo(AgentReport(state, app), since)

    private fun command(at: Long, exitCode: Int = 0) =
        FinishedCommand("make build", if (exitCode == 0) TerminalCommandOutcome.SUCCEEDED else TerminalCommandOutcome.FAILED, exitCode, 1_000, at)

    private fun populate(): List<TerminalId> {
        val project = store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        store.addWorkspace(project)
        val c = state.selection.terminal!!
        val d = store.split(c, SplitDirection.ROW)!!
        return listOf(a, b, c, d)
    }

    @Test
    fun whoWaitsForTheUserAndWhoFinishedAreListedApart() {
        val (a, b, c, d) = populate()

        val attention = state.attention(
            mapOf(a to info(AgentState.NEEDS_INPUT, 100), b to info(AgentState.DONE, 200), c to info(AgentState.WORKING, 300), d to info(AgentState.ERROR, 400)),
        )

        assertEquals(listOf(d, a), attention.needsYou.map { it.terminal.id })
        assertEquals(listOf(b), attention.finished.map { it.terminal.id })
        assertEquals(3, attention.count, "What finished needs the user too")
    }

    @Test
    fun theMostRecentComesFirst() {
        val (a, b, c) = populate()

        val attention = state.attention(mapOf(a to info(AgentState.DONE, 100), b to info(AgentState.DONE, 300), c to info(AgentState.DONE, 200)))

        assertEquals(listOf(b, c, a), attention.finished.map { it.terminal.id })
    }

    @Test
    fun terminalsAtRestOrWorkingAskForNothing() {
        val (a, b) = populate()

        val attention = state.attention(mapOf(a to info(AgentState.IDLE, 1), b to info(AgentState.WORKING, 2)))

        assertEquals(Attention(emptyList(), emptyList()), attention)
        assertEquals(0, attention.count)
    }

    @Test
    fun anItemSaysWhereTheTerminalIs() {
        val (a) = populate()

        val item = state.attention(mapOf(a to info(AgentState.NEEDS_INPUT, 1))).needsYou.single()

        assertEquals("a", item.project.name)
        assertEquals("a", item.workspace.name)
        assertEquals(a, item.terminal.id)
    }

    @Test
    fun theNextAgentThatNeedsTheUserFollowsTheOneThatHasTheFocusInTheOrderOfTheSidebar() {
        val (a, b, c, d) = populate()
        val attention = state.attention(mapOf(d to info(AgentState.NEEDS_INPUT, 1), a to info(AgentState.NEEDS_INPUT, 9), c to info(AgentState.ERROR, 5)))

        assertEquals(a, attention.next(state, from = null))
        assertEquals(c, attention.next(state, from = a))
        assertEquals(d, attention.next(state, from = c))
        // Round to the first once past the last.
        assertEquals(a, attention.next(state, from = d))
        // From a terminal that does not need the user: the first.
        assertEquals(a, attention.next(state, from = b))
    }

    @Test
    fun theNextAgentMayBeOneThatFinished() {
        val (a, b, c, _) = populate()
        val attention = state.attention(mapOf(a to info(AgentState.NEEDS_INPUT, 1), b to info(AgentState.DONE, 2), c to info(AgentState.WORKING, 3)))

        assertEquals(b, attention.next(state, from = a))
        assertEquals(a, attention.next(state, from = b))
    }

    @Test
    fun withNoAgentNeedingTheUserThereIsNowhereToJump() {
        populate()

        assertNull(state.attention(emptyMap()).next(state, null))
    }

    @Test
    fun aCommandThatEndedIsWithWhatFinishedFailedOrNot() {
        val (a, b, c) = populate()

        val attention = state.attention(
            mapOf(a to info(AgentState.DONE, 200)),
            mapOf(b to command(at = 300), c to command(at = 100, exitCode = 2)),
        )

        assertEquals(emptyList(), attention.needsYou, "A command that failed is not waiting for the user")
        assertEquals(listOf(b, a, c), attention.finished.map { it.terminal.id }, "The most recent first, commands and agents together")
        assertEquals(3, attention.count)
        val failed = attention.finished.last()
        assertEquals(AgentState.ERROR, failed.state)
        assertEquals(100, failed.since)
        assertNull(failed.info)
        assertEquals(AgentState.DONE, attention.finished.first().state)
    }

    @Test
    fun aTerminalIsListedOnceForWhatItsAgentSaysWhenItNeedsTheUser() {
        val (a, b) = populate()

        val attention = state.attention(
            mapOf(a to info(AgentState.NEEDS_INPUT, 100), b to info(AgentState.IDLE, 100)),
            mapOf(a to command(at = 300), b to command(at = 200)),
        )

        assertEquals(listOf(a), attention.needsYou.map { it.terminal.id })
        assertNull(attention.needsYou.single().command)
        assertEquals(listOf(b), attention.finished.map { it.terminal.id }, "An agent at rest says nothing that needs the user: its command is listed")
        assertEquals(2, attention.count)
    }

    @Test
    fun theJumpGoesThroughTheCommandsThatEnded() {
        val (a, b, c) = populate()
        val attention = state.attention(mapOf(a to info(AgentState.NEEDS_INPUT, 1)), mapOf(c to command(at = 2)))

        assertEquals(c, attention.next(state, from = a))
        assertEquals(a, attention.next(state, from = c))
    }

    @Test
    fun timeIsToldInTheLargestUnit() {
        val now = 10_000_000_000L
        assertEquals("now", relativeTime(now, now))
        assertEquals("now", relativeTime(now, now - 59_000))
        assertEquals("2 min", relativeTime(now, now - 2 * 60_000))
        assertEquals("59 min", relativeTime(now, now - 59 * 60_000))
        assertEquals("1 h", relativeTime(now, now - 60 * 60_000))
        assertEquals("23 h", relativeTime(now, now - 23 * 3_600_000L - 1000))
        assertEquals("1 d", relativeTime(now, now - 24 * 3_600_000L))
        assertEquals("3 d", relativeTime(now, now - 3 * 24 * 3_600_000L))
    }

    @Test
    fun aTimeInTheFutureIsNow() {
        assertEquals("now", relativeTime(100, 200_000))
    }
}
