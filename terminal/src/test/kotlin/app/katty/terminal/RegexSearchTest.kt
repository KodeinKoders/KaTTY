package app.katty.terminal

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.regex.Pattern
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegexSearchTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun matches(lines: List<String>, regex: String, flags: Int = 0) = regexMatches(lines, 10, Pattern.compile(regex, flags))

    @Test
    fun findsEveryMatchOfEveryRowByItsRowAndItsCharacters() {
        assertEquals(
            listOf(RegexMatch(10, 0, 4), RegexMatch(10, 5, 8), RegexMatch(12, 2, 6)),
            matches(listOf("foo1 foo", "none", "  foo2"), "foo\\d?"),
        )
    }

    @Test
    fun aMatchThatIsEmptyIsNotOne() {
        assertEquals(listOf(RegexMatch(10, 1, 3)), matches(listOf("baab"), "a*"))
        // And a pattern that only matches the empty string finds none, instead of one at each character.
        assertEquals(emptyList(), matches(listOf("abc"), "x*"))
    }

    @Test
    fun theStartOfALineIsTheStartOfTheRowOnly() {
        assertEquals(listOf(RegexMatch(10, 0, 1)), matches(listOf("aa"), "^a"))
    }

    @Test
    fun aMatchDoesNotGoOverTheEndOfARow() {
        assertEquals(emptyList(), matches(listOf("ab", "cd"), "b.c"))
    }

    @Test
    fun theColumnOfACharacterIsTheColumnOfItsCell() {
        // A wide character is one character on two columns, with a tail that has none; a cell without text is a blank.
        fun cell(text: String, width: TerminalCellWidth = TerminalCellWidth.NARROW) = TerminalCell(text, null, null, TerminalStyle.NONE, width)
        val cells = listOf(
            cell("中", TerminalCellWidth.WIDE), cell("", TerminalCellWidth.SPACER_TAIL),
            cell(""), cell("b"), cell("é"),
        )

        // 中, a blank, b, e, the accent: the characters of the text; and the column after the last.
        assertEquals(listOf(0, 2, 3, 4, 4, 5), columnsOfCharacters(cells).toList())
        assertEquals(2, cellSpan(cells, 0))
        assertEquals(1, cellSpan(cells, 3))
    }

    @Test
    fun aQueryThatIsNotARegularExpressionSaysWhy() {
        assertNotNull(RegexQuery.of("(", caseSensitive = false).error)
        assertNull(RegexQuery.of("(", caseSensitive = false).pattern)
        assertNull(RegexQuery.of("a+", caseSensitive = false).error)
    }

    // ---- In a session ----

    private fun start(script: String) =
        TerminalSession.start(40, 8, command = listOf("/bin/sh", "-c", script)).also { sessions += it }

    private fun TerminalFrame.text() =
        rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    private fun <T> TerminalSession.await(what: String, block: () -> T?): T = runBlocking {
        try {
            withTimeout(5_000) {
                while (true) {
                    block()?.let { return@withTimeout it }
                    kotlinx.coroutines.delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("never")
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Still waiting for $what. The screen:\n${frames.value.text()}\nThe search: ${search.state.value}", e)
        }
    }

    private fun TerminalSession.shown(text: String) {
        runBlocking { withTimeout(5_000) { frames.first { text in it.text() } } }
    }

    @Test
    fun searchesWithARegularExpressionAndShowsTheMatches() {
        val session = start("printf 'foo1 bar\\nfoo22 baz\\nnone\\n'; sleep 30")
        session.shown("none")

        session.search.setQuery("foo\\d+", regex = true)

        val frame = session.await("the matches") { session.frames.value.takeIf { it.searchMatches.size == 2 } }
        assertEquals(listOf(0..3, 0..4), frame.searchMatches.map { it.columns })
        assertEquals(listOf(0, 1), frame.searchMatches.map { it.row })
        assertEquals(2, session.search.state.value.total)
        assertEquals(null, session.search.state.value.error)
    }

    @Test
    fun findsWhatIsWrittenRightAfterItLooked() {
        val session = start("printf '\\033[?25l'; stty raw -echo; echo ready; head -c1 >/dev/null; printf 'one foo\\n'; sleep 30")
        session.shown("ready")
        session.search.setQuery("foo", regex = true)
        session.await("the search to have looked") { session.search.state.value.takeIf { it.query == "foo" } }

        // The program writes less than the time between two looks after the last one, and then nothing more.
        session.paste("x")

        session.await("the match of what came then") { session.search.state.value.takeIf { it.total == 1 } }
    }

    @Test
    fun anExpressionThatTakesTooLongFreezesNothing() {
        // A backreference after a nested repeat, on a row of a's that does not end in one: Java's matcher backtracks for longer than anyone
        // waits (27 s for 30 characters). `(a+)+$` does not: Java remembers where its loops failed.
        val session = start("stty raw -echo; printf '%s\\r\\nready\\r\\n' ${"a".repeat(35)}b; head -c1 >/dev/null; printf 'after\\r\\n'; sleep 30")
        session.await("the program to be ready") { session.frames.value.takeIf { "ready" in it.text() } }
        session.search.setQuery("(a+)+\\1$", regex = true)

        val started = System.nanoTime()
        session.search.next()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 500, "Going to the next match took ${tookMs}ms")

        session.await("the search to say it gave up") { session.search.state.value.error }
        // The program's output goes on being shown: the terminal is not held.
        session.paste("x")
        session.await("what the program wrote after") { session.frames.value.takeIf { "after" in it.text() } }
    }

    @Test
    fun movesFromAMatchToTheNextRoundTheEnds() {
        val session = start("printf 'foo1 bar\\nfoo22 baz\\nnone\\n'; sleep 30")
        session.shown("none")
        session.search.setQuery("foo\\d+", regex = true)
        session.await("the matches") { session.search.state.value.takeIf { it.total == 2 } }

        // The first is the top-most, then toward newer content, round to the first.
        session.search.next()
        session.await("the first match") { session.search.state.value.takeIf { it.selected == 1 } }
        session.await("it to be the current one") { session.frames.value.searchMatches.singleOrNull { it.current }?.takeIf { it.row == 0 } }
        session.search.next()
        session.await("the second match") { session.search.state.value.takeIf { it.selected == 2 } }
        session.search.next()
        session.await("the first match again") { session.search.state.value.takeIf { it.selected == 1 } }
        session.search.previous()
        session.await("the last match, going back round") { session.search.state.value.takeIf { it.selected == 2 } }
    }

    @Test
    fun aRegularExpressionIsCaseInsensitiveUnlessAskedNotToBe() {
        val session = start("printf 'Foo foo FOO\\n'; sleep 30")
        session.shown("Foo foo FOO")

        session.search.setQuery("foo", regex = true)
        session.await("three matches") { session.search.state.value.takeIf { it.total == 3 } }
        session.search.setQuery("foo", regex = true, caseSensitive = true)
        session.await("one match") { session.search.state.value.takeIf { it.total == 1 && it.query == "foo" } }
    }

    @Test
    fun aQueryThatIsNotARegularExpressionFindsNothingAndSaysWhy() {
        val session = start("printf 'a (b\\n'; sleep 30")
        session.shown("a (b")

        session.search.setQuery("(", regex = true)

        val state = session.await("the error") { session.search.state.value.takeIf { it.error != null } }
        assertEquals(0, state.total)
        assertTrue(session.frames.value.searchMatches.isEmpty())
        // The same text, as the text search that it is when it is not a regular expression, is found.
        session.search.setQuery("(")
        session.await("the match of the text") { session.search.state.value.takeIf { it.total == 1 && it.error == null } }
    }

    @Test
    fun theColumnsOfAMatchAfterAWideCharacterAreTheColumnsOfItsCells() {
        val session = start("printf '中文 bar\\n'; sleep 30")
        session.shown("bar")

        session.search.setQuery("bar", regex = true)

        val frame = session.await("the match") { session.frames.value.takeIf { it.searchMatches.size == 1 } }
        // Two wide characters take four cells, a blank one: the b is in the sixth.
        assertEquals(5..7, frame.searchMatches.single().columns)
    }

    @Test
    fun findsWhatTheProgramWritesAfterwards() {
        val session = start("sleep 1; printf 'later foo\\n'; sleep 30")
        session.search.setQuery("foo", regex = true)
        session.await("the search to be running") { session.search.state.value.takeIf { it.query == "foo" } }

        session.await("the match that came later") { session.search.state.value.takeIf { it.total == 1 } }
    }
}
