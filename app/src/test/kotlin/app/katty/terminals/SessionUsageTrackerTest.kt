package app.katty.terminals

import app.katty.core.Agent
import app.katty.core.SessionUsage
import app.katty.core.SessionUsageReader
import app.katty.core.TerminalId
import app.katty.terminal.ForegroundCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionUsageTrackerTest {
    private val terminal = TerminalId("t1")
    private val names = HashMap<TerminalId, String?>()
    private val pids = HashMap<TerminalId, Int>()
    private val sessions = HashMap<TerminalId, String?>()

    private inner class FakeReader : SessionUsageReader {
        val updated = HashMap<String, Long>()
        val usages = HashMap<String, SessionUsage>()
        val reads = mutableListOf<String>()
        override fun updatedAt(id: String): Long? = updated[id]
        override fun read(id: String): SessionUsage? = usages[id].also { reads += id }
    }

    private val reader = FakeReader()
    private val tracker = SessionUsageTracker(
        CoroutineScope(Dispatchers.Unconfined),
        mapOf(Agent.OPENCODE to reader),
        { names },
        { id -> pids[id]?.let { ForegroundCommand(it, listOf("opencode"), "/work", 0L, false) } },
        { sessions[it] },
    )

    private fun tick() = runBlocking { tracker.tick() }

    private fun opencode(pid: Int = 10, session: String? = "ses_a") {
        names[terminal] = "opencode"
        pids[terminal] = pid
        sessions[terminal] = session
    }

    @Test
    fun readsTheSessionOfAnAgentThatHasAReader() {
        opencode()
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(40, 1.5)

        tick()

        assertEquals(SessionUsage(40, 1.5), tracker.usages.value[terminal])
    }

    @Test
    fun readsAgainOnlyWhenTheSessionMoved() {
        opencode()
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(40, 1.5)
        tick()
        tick()
        assertEquals(1, reader.reads.size, "Nothing moved")

        reader.updated["ses_a"] = 2
        reader.usages["ses_a"] = SessionUsage(45, 1.7)
        tick()

        assertEquals(2, reader.reads.size)
        assertEquals(SessionUsage(45, 1.7), tracker.usages.value[terminal])
    }

    @Test
    fun keepsTheFirstSessionForTheSameProgram() {
        opencode()
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(10, null)
        reader.updated["ses_b"] = 5
        reader.usages["ses_b"] = SessionUsage(90, null)
        tick()

        sessions[terminal] = "ses_b"
        tick()

        assertEquals(SessionUsage(10, null), tracker.usages.value[terminal], "Another session of the directory does not take its place")
    }

    @Test
    fun anotherProgramIsAnotherSession() {
        opencode()
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(10, null)
        reader.updated["ses_b"] = 5
        reader.usages["ses_b"] = SessionUsage(90, null)
        tick()

        opencode(pid = 11, session = "ses_b")
        tick()

        assertEquals(SessionUsage(90, null), tracker.usages.value[terminal])
    }

    @Test
    fun theEntryGoesWhenTheShellHasTheTerminalAgain() {
        opencode()
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(10, null)
        tick()

        names[terminal] = "zsh"
        tick()

        assertNull(tracker.usages.value[terminal])
    }

    @Test
    fun aSessionThatIsNotKnownYetIsReadWhenItAppears() {
        opencode(session = null)
        tick()
        assertNull(tracker.usages.value[terminal])

        sessions[terminal] = "ses_a"
        reader.updated["ses_a"] = 1
        reader.usages["ses_a"] = SessionUsage(5, 0.1)
        tick()

        assertEquals(SessionUsage(5, 0.1), tracker.usages.value[terminal])
    }

    @Test
    fun anAgentWithNoReaderIsNotLookedAt() {
        names[terminal] = "claude"
        pids[terminal] = 3
        sessions[terminal] = "abc"

        tick()

        assertEquals(emptyMap(), tracker.usages.value)
    }

    @Test
    fun aReadThatFailsIsNoReadingAndIsTriedAgain() {
        opencode()
        reader.updated["ses_a"] = 1
        tick()
        assertNull(tracker.usages.value[terminal])

        reader.usages["ses_a"] = SessionUsage(7, null)
        tick()

        assertEquals(SessionUsage(7, null), tracker.usages.value[terminal])
    }
}
