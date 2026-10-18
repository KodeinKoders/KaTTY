package app.katty.terminal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedCommandTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    @Test
    fun quotesWhatAShellWouldSplitOrExpand() {
        assertEquals("emacs ~/x", shellLine(listOf("emacs", "~/x"), "zsh")?.replace("'~/x'", "~/x"), "A tilde is quoted, so that it is not expanded twice")
        assertEquals("emacs notes.txt", shellLine(listOf("emacs", "notes.txt"), "zsh"))
        assertEquals("""echo 'a b' '' '${'$'}HOME' 'it'\''s'""", shellLine(listOf("echo", "a b", "", "\$HOME", "it's"), "zsh"))
        assertEquals("""echo 'it\'s' 'a\\b'""", shellLine(listOf("echo", "it's", "a\\b"), "fish"))
        assertEquals("""echo 'a\b'""", shellLine(listOf("echo", "a\\b"), "bash"))
    }

    @Test
    fun refusesWhatHoldsAControlCharacter() {
        assertNull(shellLine(listOf("echo", "a\u001bb"), "zsh"), "An escape would be a key of the line editor")
        assertNull(shellLine(listOf("echo", "a\nb"), "zsh"), "A newline would run the line")
        assertNull(TypedCommand(listOf("echo", "\r"), true).toInput("zsh"))
    }

    @Test
    fun endsTheLineWithReturnOnlyToRunIt() {
        assertEquals("ls -l\r", TypedCommand(listOf("ls", "-l"), true).toInput("zsh")!!.decodeToString())
        assertEquals("ls -l", TypedCommand(listOf("ls", "-l"), false).toInput("zsh")!!.decodeToString())
        assertFailsWith<IllegalArgumentException> { TypedCommand(emptyList(), true) }
    }

    private fun emptyHome(): Path = Files.createTempDirectory("katty-home").also {
        Files.createFile(it.resolve(".zshrc"))
        it.toFile().deleteOnExit()
    }

    private fun start(command: TypedCommand, shellIntegration: Boolean = true): TerminalSession =
        TerminalSession.start(
            80, 10,
            command = listOf("/bin/zsh", "-l"),
            environment = mapOf("HOME" to emptyHome().toString(), "PS1" to "$ "),
            shellIntegration = shellIntegration,
            typeAtPrompt = command,
        ).also { sessions += it }

    private fun TerminalSession.screen(): List<String> =
        frames.value.rows.map { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    private fun TerminalSession.await(what: String, condition: (List<String>) -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition(screen())) {
            check(System.nanoTime() < deadline) { "Still waiting for $what. The screen:\n${screen().joinToString("\n")}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun runsTheCommandAtTheFirstPromptAsIfTheUserTypedIt() {
        val session = start(TypedCommand(listOf("printf", "%s\\n", "it's typed"), run = true))

        session.await("the output of the command") { "it's typed" in it }
        // Over the shell: the shell is there when the program ends.
        session.await("the prompt after it") { rows -> rows.last { it.isNotEmpty() }.endsWith("%") }
    }

    @Test
    fun leavesTheCommandOnTheLineWhenItIsNotToRun() {
        val session = start(TypedCommand(listOf("echo", "waits-for-return"), run = false))

        session.await("the line to be typed") { rows -> rows.any { it.endsWith("% echo waits-for-return") } }
        assertTrue("waits-for-return" !in session.screen(), "It did not run")
        session.sendText("\r")
        session.await("it to run when the user says so") { "waits-for-return" in it }
    }

    @Test
    fun typesItAfterASecondWhenThereAreNoPromptMarks() {
        val session = start(TypedCommand(listOf("echo", "no-marks"), run = true), shellIntegration = false)

        session.await("the output of the command") { "no-marks" in it }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
