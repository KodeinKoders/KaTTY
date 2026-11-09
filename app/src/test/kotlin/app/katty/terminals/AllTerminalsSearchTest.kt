package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllTerminalsSearchTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // A terminal whose program writes [script] (with `printf`), and waits.
    private fun terminal(id: String, script: String, rows: Int = 10): SearchTarget {
        val session = TerminalSession.start(60, rows, command = listOf("/bin/sh", "-c", "$script; echo END; sleep 30"), shellIntegration = false).also { sessions += it }
        await("its output", session) { "END" in session.readText() }
        return SearchTarget(TerminalId(id), session)
    }

    private fun await(what: String, session: TerminalSession, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what, the screen:\n${session.readText()}" }
            Thread.sleep(10)
        }
    }

    private fun AllTerminalsSearch.results(query: String, targets: List<SearchTarget>) = runBlocking { search(query, targets).last() }

    @Test
    fun theRowsOfEachTerminalAreFoundTheLastFirstInTheOrderOfTheTerminals() {
        val a = terminal("a", """printf 'alpha\r\nneedle here\r\nbeta\r\nNEEDLE again\r\n'""")
        val b = terminal("b", """printf 'nothing\r\nthe needle of b\r\n'""").also { it.session.setVisible(false) }
        val none = terminal("none", """printf 'nothing at all\r\n'""")

        val results = AllTerminalsSearch().results("needle", listOf(a, none, b))

        assertEquals(listOf("a", "b"), results.terminals.map { it.terminal.value }, "A terminal without it is not listed")
        assertEquals(listOf(3L to "NEEDLE again", 1L to "needle here"), results.terminals[0].matches.map { it.row to it.line })
        assertEquals(listOf(1L to "the needle of b"), results.terminals[1].matches.map { it.row to it.line }, "A hidden terminal is searched too")
        assertEquals(3, results.total)
        assertFalse(results.searching)
        assertFalse(results.capped)
    }

    @Test
    fun whatIsFoundIsGivenAfterEachTerminal() {
        val a = terminal("a", """printf 'needle\r\n'""")
        val b = terminal("b", """printf 'needle\r\n'""")

        val steps = runBlocking { AllTerminalsSearch().search("needle", listOf(a, b)).toList() }

        assertEquals(listOf(0, 1, 2, 2), steps.map { it.terminals.size })
        assertEquals(listOf(true, true, true, false), steps.map { it.searching })
    }

    @Test
    fun aTerminalGivesSoManyRowsAndAllOfThemSoManyMore() {
        val a = terminal("a", """printf 'x 1\r\nx 2\r\nx 3\r\n'""")
        val b = terminal("b", """printf 'x 4\r\n'""")

        val perTerminal = AllTerminalsSearch(perTerminal = 2).results("x", listOf(a, b))
        assertEquals(listOf("x 3", "x 2"), perTerminal.terminals[0].matches.map { it.line })
        assertTrue(perTerminal.terminals[0].more)
        assertFalse(perTerminal.terminals[1].more)

        val total = AllTerminalsSearch(maxTotal = 2).results("x", listOf(a, b))
        assertEquals(listOf("a"), total.terminals.map { it.terminal.value })
        assertTrue(total.capped)
    }

    @Test
    fun aLongHistoryIsReadInPiecesWithTheRightRows() {
        val long = terminal("long", "seq 1 1200", rows = 5)

        val results = AllTerminalsSearch(chunkRows = 500).results("1150", listOf(long))

        val match = results.terminals.single().matches.single()
        assertEquals("1150", match.line)
        assertEquals(listOf("1150"), long.session.readLines(match.row, match.row), "Its row is where it is")
    }

    @Test
    fun aTerminalThatWroteSinceIsReadAgain() {
        val session = TerminalSession.start(
            60, 10, command = listOf("/bin/sh", "-c", "stty raw -echo; printf 'needle one\\r\\nREADY\\r\\n'; head -c1 >/dev/null; printf 'needle two\\r\\n'; sleep 30"),
            shellIntegration = false,
        ).also { sessions += it }
        await("its output", session) { "READY" in session.readText() }
        val target = SearchTarget(TerminalId("a"), session)
        val search = AllTerminalsSearch()
        assertEquals(1, search.results("needle", listOf(target)).total)

        session.paste("x")
        await("what it wrote then", session) { "needle two" in session.readText() }

        assertEquals(listOf("needle two", "needle one"), search.results("needle", listOf(target)).terminals.single().matches.map { it.line })
    }

    @Test
    fun anEmptyQueryFindsNothing() {
        val a = terminal("a", """printf 'needle\r\n'""")

        val results = AllTerminalsSearch().results("", listOf(a))

        assertTrue(results.terminals.isEmpty())
        assertFalse(results.searching)
    }

    @Test
    fun theSelectedTerminalIsSearchedFirstThenTheOthersAsTheSidebarListsThem() {
        var counter = 0
        val store = KattyStore(newId = { "id${counter++}" })
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.addProject("/var")
        val third = store.state.value.selection.terminal!!
        store.focus(second)

        assertEquals(listOf(second, first, third), store.state.value.searchOrder())
    }
}
