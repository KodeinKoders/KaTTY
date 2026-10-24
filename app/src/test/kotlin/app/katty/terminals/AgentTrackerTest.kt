package app.katty.terminals

import app.katty.core.AgentState
import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTrackerTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var time = 1000L
    private val tracker = AgentTracker(scope, clock = { time })
    private val scripts = ArrayDeque<String>()

    private val registry = SessionRegistry(
        store, scope, starter = kotlinx.coroutines.Dispatchers.Unconfined, loadTheme = { null },
        start = { spec, _ ->
            TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", scripts.removeFirstOrNull() ?: "sleep 60"), directory = spec.directory, shellIntegration = false)
        },
    )

    @AfterTest
    fun close() {
        tracker.close()
        registry.close()
        scope.cancel()
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun report(vararg fields: String) = "printf '\\033]7501;${fields.joinToString(":")}\\033\\\\'"

    private fun start(): app.katty.core.TerminalId {
        store.addProject("/tmp")
        registry.sync(store.state.value)
        tracker.sync(registry.handles.value)
        return store.state.value.selection.terminal!!
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    @Test
    fun reportsThatComeAtTheSameMomentFromManyTerminalsAreAllKept() {
        // Each session reports on its own thread: two updates that read the same map, and wrote it one after the other, lost the first.
        repeat(16) { scripts += "sleep 1; ${report("state=working", "app=claude-code")}; sleep 60" }
        store.addProject("/tmp")
        repeat(15) { store.split(store.state.value.selection.terminal!!, SplitDirection.ROW) }
        registry.sync(store.state.value)
        tracker.sync(registry.handles.value)
        val ids = store.state.value.allTerminals().map { it.terminal.id }

        await("every terminal to be working") { ids.all { tracker.infos.value[it]?.state == AgentState.WORKING } }
    }

    @Test
    fun aTerminalThatSaysNothingIsAtRest() {
        scripts += "sleep 60"
        val id = start()

        await("the terminal is followed") { tracker.infos.value[id] != null }

        assertEquals(AgentState.IDLE, tracker.infos.value.getValue(id).state)
    }

    @Test
    fun followsWhatTheProgramReports() {
        scripts += "${report("state=working", "app=claude-code", "msg=${b64("Running the tests")}")}; sleep 60"
        val id = start()

        await("working") { tracker.infos.value[id]?.state == AgentState.WORKING }

        val info = tracker.infos.value.getValue(id)
        assertEquals("claude-code", info.report.app)
        assertEquals("Running the tests", info.report.note)
        assertEquals(app.katty.core.Agent.CLAUDE, info.report.agent)
    }

    @Test
    fun aTerminalThatWaitsForTheUserNeedsInput() {
        scripts += "${report("state=blocked", "kind=permission", "app=codex", "msg=${b64("Apply this patch?")}")}; sleep 60"
        val id = start()

        await("blocked") { tracker.infos.value[id]?.state == AgentState.NEEDS_INPUT }

        assertEquals("Apply this patch?", tracker.infos.value.getValue(id).report.note)
    }

    @Test
    fun theTimeIsWhenTheStateChangedNotWhenItWasSaidAgain() {
        scripts += "${report("state=working", "app=a")}; sleep 1; ${report("state=working", "app=a", "msg=${b64("more")}")}; sleep 60"
        val id = start()

        await("working") { tracker.infos.value[id]?.state == AgentState.WORKING }
        val first = tracker.infos.value.getValue(id).since
        time = 5000
        await("the note changed") { tracker.infos.value[id]?.report?.note == "more" }

        assertEquals(first, tracker.infos.value.getValue(id).since, "Still working: the time did not move")
    }

    @Test
    fun aChangeOfStateIsATransitionWithTheStateItLeft() {
        scripts += "sleep 1; ${report("state=working", "app=a")}; sleep 1; ${report("state=done", "app=a")}; sleep 60"
        val received = java.util.concurrent.CopyOnWriteArrayList<AgentTransition>()
        scope.launch { tracker.transitions.collect { received += it } }
        Thread.sleep(200)
        val id = start()

        await("two transitions") { received.size >= 2 }

        assertEquals(listOf(AgentState.IDLE to AgentState.WORKING, AgentState.WORKING to AgentState.DONE), received.take(2).map { it.from to it.info.state })
        assertEquals(id, received.first().terminal)
    }

    @Test
    fun markingAnAgentSeenTakesWhatItFinishedOff() {
        scripts += "${report("state=done", "app=claude-code")}; sleep 60"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }

        tracker.markSeen(id)

        await("at rest") { tracker.infos.value[id]?.state == AgentState.IDLE }
        // What named the program was the record that was taken off, and the agent is still running (the foreground program is not the shell):
        // it is still that agent, at rest. The name of its program cannot say it (native Claude Code's is its version number).
        assertEquals("claude-code", tracker.infos.value.getValue(id).report.app)
    }

    @Test
    fun aProgramThatIsNotAKnownAgentStaysAtRestAfterItsDoneWasSeen() {
        scripts += "${report("state=done", "app=my-tool")}; sleep 60"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }

        tracker.markSeen(id)

        await("at rest, still reporting") { tracker.infos.value[id]?.let { it.state == AgentState.IDLE && it.report.reporting } == true }
        assertEquals("my-tool", tracker.infos.value.getValue(id).report.app)
        assertNull(tracker.infos.value.getValue(id).report.agent)
    }

    @Test
    fun aProgramThatNamesNothingStaysAtRestAfterItsDoneWasSeen() {
        scripts += "${report("state=done")}; sleep 60"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }

        tracker.markSeen(id)

        await("at rest, still reporting") { tracker.infos.value[id]?.let { it.state == AgentState.IDLE && it.report.reporting } == true }
        assertNull(tracker.infos.value.getValue(id).report.app)
    }

    @Test
    fun theAgentIsForgottenWhenTheShellHasTheTerminalAgain() {
        // It reports, waits for a line (in a program of its own, `head`: the shell's own `read` would be the shell having the terminal), and then
        // the shell takes the terminal: the agent has ended. A line and not a time, which a slow machine could take up before the test looks.
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        scripts += "${report("state=done", "app=claude-code")}; head -n 1 >/dev/null; exec $shell -f -i"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }
        tracker.markSeen(id)
        await("at rest, still the agent") { tracker.infos.value[id]?.let { it.state == AgentState.IDLE && it.report.app == "claude-code" } == true }

        registry.handles.value.getValue(id).session!!.sendText("\r")

        await("the shell has the terminal, the agent is gone") { tracker.infos.value[id]?.report?.reporting == false }

        assertEquals(AgentState.IDLE, tracker.infos.value.getValue(id).state)
    }

    @Test
    fun anAgentThatEndedAtRestLeavesNoCard() {
        // It reports `idle`, which the terminal keeps after the program ends, and the shell has the terminal.
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        scripts += "${report("state=idle", "app=antigravity")}; sleep 1; exec $shell -f -i"
        val id = start()
        await("reporting, at rest") { tracker.infos.value[id]?.report?.reporting == true }

        await("the shell has the terminal, the card is gone") { tracker.infos.value[id]?.report?.reporting == false }
    }

    @Test
    fun theIdleRecordOfAnAgentThatEndedIsNotTheNextProgramsCard() {
        // Crush, agy and OpenCode leave an `idle` record when they end. The shell has the terminal, and then emacs or aider takes it: the
        // record is the old program's, and must not make a card for it ("Crush") around a program that says nothing.
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        scripts += "${report("state=idle", "app=crush")}; sleep 1; exec $shell -f -i"
        val id = start()
        await("reporting, at rest") { tracker.infos.value[id]?.report?.reporting == true }
        await("the shell has the terminal, the card is gone") { tracker.infos.value[id]?.report?.reporting == false }
        val handle = registry.handles.value.getValue(id)
        await("the shell has the terminal") { registry.processNames.value[id] == handle.shellName }

        handle.session!!.sendText("sleep 30\r")

        await("another program has the terminal") { registry.processNames.value[id] == "sleep" }
        Thread.sleep(500)
        assertEquals(false, tracker.infos.value.getValue(id).report.reporting, "the program that ended is back as a card")
    }

    @Test
    fun anAgentThatEndedBeforeItsFinishedStateWasSeenLeavesNoGhostOfItInTheShell() {
        // It reports `done` and ends: the shell has the terminal, and the `done` record is still there. Seeing it then must leave a plain
        // shell, not "that agent at rest" for a terminal that runs a shell.
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        scripts += "${report("state=done", "app=claude-code")}; sleep 1; exec $shell -f -i"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }
        // Wait for the shell to have the terminal: the process is what the registry says it is.
        await("the shell has the terminal") { registry.processNames.value[id] == registry.handles.value.getValue(id).shellName }
        Thread.sleep(300)

        tracker.markSeen(id)

        await("at rest") { tracker.infos.value[id]?.state == AgentState.IDLE }
        assertEquals(false, tracker.infos.value.getValue(id).report.reporting, "a shell is shown as an agent")
    }

    @Test
    fun anAgentThatWasSeenReportsAgain() {
        scripts += "${report("state=done", "app=claude-code")}; sleep 1; ${report("state=working", "app=claude-code")}; sleep 60"
        val id = start()
        await("done") { tracker.infos.value[id]?.state == AgentState.DONE }
        tracker.markSeen(id)

        await("working again") { tracker.infos.value[id]?.state == AgentState.WORKING }

        assertEquals("claude-code", tracker.infos.value.getValue(id).report.app)
    }

    @Test
    fun theTimeIsThatOfTheColumnSoAWaitThatBecomesAFailureKeepsIt() {
        // Waiting, then (after the time has moved on) failing: both are the "Needs input" column.
        scripts += "${report("state=blocked", "app=claude-code")}; sleep 1; ${report("state=error", "app=claude-code")}; sleep 60"
        val id = start()
        await("waiting") { tracker.infos.value[id]?.state == AgentState.NEEDS_INPUT }
        val arrived = tracker.infos.value.getValue(id).since
        time = 9_000L

        await("failed") { tracker.infos.value[id]?.state == AgentState.ERROR }

        assertEquals(arrived, tracker.infos.value.getValue(id).since)
    }

    @Test
    fun markingSeenLeavesWhatIsStillWorking() {
        scripts += "${report("state=working", "app=a")}; sleep 60"
        val id = start()
        await("working") { tracker.infos.value[id]?.state == AgentState.WORKING }

        tracker.markSeen(id)
        Thread.sleep(200)

        assertEquals(AgentState.WORKING, tracker.infos.value.getValue(id).state)
    }

    @Test
    fun markingEverythingSeenDoesItForEveryTerminal() {
        scripts += "${report("state=done", "app=a")}; sleep 60"
        scripts += "${report("state=error", "app=b")}; sleep 60"
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        registry.sync(store.state.value)
        tracker.sync(registry.handles.value)
        await("both report") { tracker.infos.value[first]?.state == AgentState.DONE && tracker.infos.value[second]?.state == AgentState.ERROR }

        tracker.markAllSeen()

        await("both at rest") { tracker.infos.value.values.all { it.state == AgentState.IDLE } }
    }

    @Test
    fun aTerminalThatIsGoneIsNotFollowedAnyMore() {
        scripts += "${report("state=working", "app=a")}; sleep 60"
        val id = start()
        await("working") { tracker.infos.value[id]?.state == AgentState.WORKING }
        store.addWorkspace(store.state.value.selection.project!!)

        store.closeTerminal(id)
        registry.sync(store.state.value)
        tracker.sync(registry.handles.value)

        assertNull(tracker.infos.value[id])
    }

    @Test
    fun aShellThatWasStartedAgainStartsAtRest() {
        scripts += "${report("state=working", "app=a")}; sleep 60"
        scripts += "sleep 60"
        val id = start()
        await("working") { tracker.infos.value[id]?.state == AgentState.WORKING }

        registry.restart(id)
        tracker.sync(registry.handles.value)

        await("at rest") { tracker.infos.value[id]?.state == AgentState.IDLE }
    }

}
