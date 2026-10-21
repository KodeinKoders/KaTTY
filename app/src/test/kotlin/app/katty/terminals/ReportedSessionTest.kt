package app.katty.terminals

import app.katty.core.Agent
import app.katty.core.sessionOf
import app.katty.terminal.TerminalSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportedSessionTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun run(script: String) = TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", script), shellIntegration = false).also { sessions += it }

    private fun TerminalSession.await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    // What OpenCode 2.1 writes: its own record (no id), and one for each session that works, with the session's id as the record's id. The text fields are
    // base64, as the protocol has them.
    private fun report(vararg fields: String) = "printf '\\033]7501;${fields.joinToString(":")}\\033\\\\'"

    @Test
    fun theSessionThatAnOpenCodeSaysItWorksIsTheOneItIsIn() {
        val session = run("${report("state=working", "app=opencode")}; ${report("state=working", "id=ses_ea4c779d3ffefwIVozIKyv7V6f", "app=opencode", "title=${java.util.Base64.getEncoder().encodeToString("Fix the build".toByteArray())}")}; sleep 30")

        session.await("the record of the session") { session.programStatus.value.any { it.id == "ses_ea4c779d3ffefwIVozIKyv7V6f" } }

        assertEquals("ses_ea4c779d3ffefwIVozIKyv7V6f", session.programStatus.value.sessionOf(Agent.OPENCODE))
    }

    @Test
    fun aProgramThatSaysOnlyThatItIsAtRestSaysNoSession() {
        val session = run("${report("state=idle", "app=opencode")}; sleep 30")

        session.await("its record") { session.programStatus.value.isNotEmpty() }

        assertNull(session.programStatus.value.sessionOf(Agent.OPENCODE))
    }
}
