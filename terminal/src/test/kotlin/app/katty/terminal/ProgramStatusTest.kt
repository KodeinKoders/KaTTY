package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramStatusTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // --- The records, which are pure ---

    private var now = 1000L
    private val store = ProgramStatusStore(limit = 64, clock = { now++ })

    private fun put(
        id: String,
        state: TerminalProgramState = TerminalProgramState.WORKING,
        app: String = "",
        title: String = "",
        message: String = "",
        progress: Int? = null,
    ) = store.report(id, false, state, TerminalProgramKind.NONE, progress, app, title, message)

    private fun clear(id: String) = store.report(id, true, TerminalProgramState.IDLE, TerminalProgramKind.NONE, null, "", "", "")

    private fun ids() = store.snapshot().map { it.id }

    @Test
    fun whatTheStorePublishesIsWhatItHasAfterEveryChange() {
        val store = ProgramStatusStore()
        fun put(id: String, state: TerminalProgramState = TerminalProgramState.WORKING) =
            store.report(id, false, state, TerminalProgramKind.NONE, null, "app", "", "")

        put("a"); assertEquals(store.snapshot(), store.state.value)
        put("a/b", TerminalProgramState.DONE); assertEquals(store.snapshot(), store.state.value)
        store.promptStarted(); assertEquals(store.snapshot(), store.state.value)
        store.report("a", true, TerminalProgramState.IDLE, TerminalProgramKind.NONE, null, "", "", ""); assertEquals(store.snapshot(), store.state.value)
        assertEquals(emptyList(), store.state.value)
    }

    @Test
    fun aReportAndAClearAtTheSameMomentPublishTheirLastState() {
        // The reader reports, and the host clears what it showed, on two threads.
        val store = ProgramStatusStore()
        val reporter = Thread { repeat(5_000) { store.report("r$it", false, TerminalProgramState.DONE, TerminalProgramKind.NONE, null, "app", "", "") } }
        val clearer = Thread { repeat(5_000) { store.report("", true, TerminalProgramState.IDLE, TerminalProgramKind.NONE, null, "", "", "") } }
        reporter.start(); clearer.start()
        reporter.join(); clearer.join()

        assertEquals(store.snapshot(), store.state.value)
    }

    @Test
    fun aReportReplacesItsRecordAsAWhole() {
        put("", title = "Build", message = "compiling", progress = 40)
        put("", state = TerminalProgramState.DONE)

        val record = store.snapshot().single()
        assertEquals(TerminalProgramState.DONE, record.state)
        assertEquals("", record.title, "What the report left out is gone")
        assertEquals(null, record.progress)
    }

    @Test
    fun clearingARecordRemovesTheOnesBeneathItAndNotTheOnesThatOnlyLookLikeIt() {
        put("build")
        put("build/test")
        put("build/test/unit")
        put("builder")
        put("deploy")

        assertTrue(clear("build"))

        assertEquals(listOf("builder", "deploy"), ids())
    }

    @Test
    fun clearingWithNoIdRemovesEverything() {
        put("a")
        put("b")

        assertTrue(clear(""))
        assertTrue(store.snapshot().isEmpty())
        assertFalse(clear(""), "There was nothing to remove")
    }

    @Test
    fun aRecordWithoutAnAppTakesTheNearestAncestorThatHasOne() {
        put("", app = "deploy")
        put("us-east")
        put("us-east/db", app = "")
        put("eu/west")
        put("own", app = "other")

        val apps = store.snapshot().associate { it.id to it.app }

        assertEquals("deploy", apps["us-east"])
        assertEquals("deploy", apps["us-east/db"])
        assertEquals("deploy", apps["eu/west"], "The parent eu does not exist: the root is the nearest")
        assertEquals("other", apps["own"])
    }

    @Test
    fun anAppIsInheritedFromTheNearestAncestorNotTheRoot() {
        put("", app = "deploy")
        put("a", app = "inner")
        put("a/b")

        assertEquals("inner", store.snapshot().first { it.id == "a/b" }.app)
    }

    @Test
    fun aNewPromptEndsWhatWasBeingWorkedOnOrWaitedFor() {
        put("a", state = TerminalProgramState.WORKING)
        put("b", state = TerminalProgramState.BLOCKED)
        put("c", state = TerminalProgramState.DONE)
        put("d", state = TerminalProgramState.ERROR)

        assertTrue(store.promptStarted())

        assertEquals(listOf("c", "d"), ids())
        assertFalse(store.programExited(), "Nothing left to end")
    }

    @Test
    fun thereAreNoMoreThanTheLimitAndTheOneUpdatedLongestAgoGoesFirst() {
        val small = ProgramStatusStore(limit = 4, clock = { now++ })
        fun report(id: String) = small.report(id, false, TerminalProgramState.WORKING, TerminalProgramKind.NONE, null, "", "", "")
        report("a")
        report("b")
        report("c")
        report("d")
        // a is reported again: b is now the one that was updated the longest ago.
        report("a")
        report("e")

        assertEquals(listOf("a", "c", "d", "e"), small.snapshot().map { it.id })
    }

    @Test
    fun theDirectionMarksAndTheInvisibleCharactersAreRemovedFromWhatPeopleRead() {
        put("", title = "ok‮evil​", message = "a⁦b﻿c")

        val record = store.snapshot().single()
        assertEquals("okevil", record.title)
        assertEquals("abc", record.message)
    }

    @Test
    fun theOtherInvisibleCharactersAndTheControlsAreRemovedToo() {
        // The soft hyphen, the combining grapheme joiner, the Mongolian vowel separator, the Hangul fillers, a bell and C1's next line.
        put("", title = "a\u00ADb\u034Fc\u180Ed\u115Fe\u1160f\u3164g\uFFA0h\u0007i\u009Bj")

        assertEquals("abcdefghij", store.snapshot().single().title)
    }

    @Test
    fun aLineBreakIsASpaceSinceWhatIsShownIsOneLine() {
        put("", message = "one\ntwo\r\nthree\u2028four\u2029five\u0085six")

        assertEquals("one two  three four five six", store.snapshot().single().message)
    }

    @Test
    fun aTitleOrAMessageThatIsTooLongIsCutWithoutBreakingACharacter() {
        put("", title = "x".repeat(255) + "😀" + "y".repeat(1000), message = "z".repeat(10_000))

        val record = store.snapshot().single()
        assertEquals("x".repeat(255), record.title, "The emoji that would be cut in half is left out")
        assertEquals(256, record.message.length)
    }

    @Test
    fun theJoinersThatEmojiNeedAreKept() {
        put("", message = "👩‍💻")

        assertEquals("👩‍💻", store.snapshot().single().message)
    }

    // --- In a session ---

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun start(script: String, rows: Int = 5): TerminalSession =
        TerminalSession.start(80, rows, command = listOf("/bin/sh", "-c", script), shellIntegration = false).also { sessions += it }

    private fun <T> TerminalSession.await(what: String, flow: kotlinx.coroutines.flow.Flow<T>, condition: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { flow.first(condition) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    @Test
    fun aReportIsAFlowOfRecordsWithWhatTheProgramSaid() {
        val session = start(
            """printf '\033]7501;state=blocked:kind=permission:app=terraform:msg=${b64("Apply?")}\033\\'; sleep 30""",
        )

        val records = session.await("a record", session.programStatus) { it.isNotEmpty() }

        val record = records.single()
        assertEquals("", record.id)
        assertEquals(TerminalProgramState.BLOCKED, record.state)
        assertEquals(TerminalProgramKind.PERMISSION, record.kind)
        assertEquals("terraform", record.app)
        assertEquals("Apply?", record.message)
        assertEquals(null, record.progress)
    }

    @Test
    fun aProgressAndAChildRecordWithAnAppFromTheParent() {
        val session = start(
            """printf '\033]7501;state=working:app=build:progress=40\033\\'; printf '\033]7501;state=working:id=build/test\033\\'; sleep 30""",
        )

        val records = session.await("two records", session.programStatus) { it.size == 2 }

        assertEquals(listOf("", "build/test"), records.map { it.id })
        assertEquals(40, records[0].progress)
        assertEquals("build", records[1].app, "From the parent")
    }

    @Test
    fun aClearReportRemovesTheRecord() {
        val session = start(
            """printf '\033]7501;state=working:app=a\033\\'; sleep 1; printf '\033]7501;state=clear\033\\'; sleep 30""",
        )
        session.await("a record", session.programStatus) { it.isNotEmpty() }

        session.await("it to be cleared", session.programStatus) { it.isEmpty() }
    }

    @Test
    fun aResetRemovesTheRecords() {
        val session = start("""printf '\033]7501;state=done:app=a\033\\'; sleep 1; printf '\033c'; sleep 30""")
        session.await("a record", session.programStatus) { it.isNotEmpty() }

        session.await("it to be cleared", session.programStatus) { it.isEmpty() }
    }

    @Test
    fun theHostCanRemoveWhatItSaw() {
        val session = start("""printf '\033]7501;state=done:app=a\033\\'; sleep 30""")
        session.await("a record", session.programStatus) { it.isNotEmpty() }

        session.clearProgramStatus()

        assertTrue(session.programStatus.value.isEmpty())
    }

    @Test
    fun theProgramExitingEndsWhatItWasWorkingOn() {
        val session = start("""printf '\033]7501;state=working:app=a\033\\'; printf '\033]7501;state=done:id=x\033\\'; sleep 1""")

        val records = session.await("only what is done", session.programStatus) { it.size == 1 && it[0].state == TerminalProgramState.DONE }

        assertEquals("x", records.single().id)
    }

    @Test
    fun theTerminalAnswersTheQueryOfTheSupportOfTheProtocol() {
        // The program reads what the terminal answers and shows it, in hex, on the screen.
        val session = start("""stty raw -echo; printf '\033]7501;?\033\\'; head -c 1 | od -An -c; sleep 30""")

        val frame = session.await("an answer", session.frames) { f -> f.rows.any { r -> r.cells.joinToString("") { it.text }.contains("033") } }

        assertTrue(frame.rows.any { r -> r.cells.joinToString("") { it.text }.contains("033") })
    }

    @Test
    fun aRequestToResizeTheWindowIsAnEventAndNothingIsResized() {
        val session = start("""sleep 1; printf '\033[8;30;100t\033[8;0;50t'; sleep 30""")
        val events = mutableListOf<TerminalEvent>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launchCollect(session, events)

        try {
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
            while (events.filterIsInstance<TerminalEvent.ResizeRequested>().size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        } finally {
            collector.cancel()
        }

        assertEquals(
            listOf(TerminalEvent.ResizeRequested(100, 30), TerminalEvent.ResizeRequested(50, null)),
            synchronized(events) { events.filterIsInstance<TerminalEvent.ResizeRequested>() },
        )
        assertEquals(80, session.frames.value.cols, "The terminal is the host's to resize")
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(session: TerminalSession, into: MutableList<TerminalEvent>) = launch {
        session.events.collect { synchronized(into) { into += it } }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
