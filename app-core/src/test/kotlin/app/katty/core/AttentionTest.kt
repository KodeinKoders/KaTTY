package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttentionTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun info(state: AgentState, since: Long, app: String = "claude-code") = AgentInfo(AgentReport(state, app), since)

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
        assertEquals(2, attention.count)
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
        assertEquals("workspace", item.workspace.name)
        assertEquals(a, item.terminal.id)
    }

    @Test
    fun theNextWaitingAgentFollowsTheOneThatHasTheFocusInTheOrderOfTheSidebar() {
        val (a, b, c, d) = populate()
        val attention = state.attention(mapOf(d to info(AgentState.NEEDS_INPUT, 1), a to info(AgentState.NEEDS_INPUT, 9), c to info(AgentState.ERROR, 5)))

        assertEquals(a, attention.nextWaiting(state, from = null))
        assertEquals(c, attention.nextWaiting(state, from = a))
        assertEquals(d, attention.nextWaiting(state, from = c))
        // Round to the first once past the last.
        assertEquals(a, attention.nextWaiting(state, from = d))
        // From a terminal that does not wait: the first.
        assertEquals(a, attention.nextWaiting(state, from = b))
    }

    @Test
    fun withNoAgentWaitingThereIsNowhereToJump() {
        populate()

        assertNull(state.attention(emptyMap()).nextWaiting(state, null))
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
