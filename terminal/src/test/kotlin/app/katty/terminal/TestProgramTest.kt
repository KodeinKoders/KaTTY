package app.katty.terminal

import org.junit.Assume.assumeFalse
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TestProgram] says what `/bin/sh` says, for the scripts that the tests give it: compared on a Unix system, where both are there. */
class TestProgramTest {
    private fun output(command: List<String>, input: String = ""): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val text = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$command ended")
        return text
    }

    // [blanks]: whether the blanks count, which they do not for od, whose columns differ from one system to the next and which the tests take out.
    private fun sameAsSh(script: String, input: String = "", blanks: Boolean = true) {
        assumeFalse("No /bin/sh to compare with", testsOnWindows)
        fun String.compared() = if (blanks) this else replace(Regex("\\s+"), " ").trim()
        assertEquals(output(listOf("/bin/sh", "-c", script), input).compared(), output(testProgram(script), input).compared(), script)
    }

    @Test
    fun writesWhatPrintfAndEchoWrite() {
        sameAsSh("""printf '\033[1mbold\033[0m é 😀 100%% %s=%d\r\n' name 42; echo ready; echo hi""")
        sameAsSh("""printf '\0033]7;file://localhost/tmp/a%%20b\007'; printf 'tab\there\\back\n'""")
        sameAsSh("""printf '%s\n' one two three""")
    }

    @Test
    fun readsWhatHeadOdAndTrRead() {
        sameAsSh("head -c3 | od -An -c", input = "a\u001b\r\nrest", blanks = false)
        sameAsSh("head -c4 | od -An -tx1", input = "\u001b[Aé", blanks = false)
        sameAsSh("""head -c6 | tr -d '\033' | od -An -c""", input = "\u001b[I\u001b[Oxyz", blanks = false)
        sameAsSh("head -c1 >/dev/null; echo after", input = "x")
    }

    @Test
    fun countsAsSeqAndYesDo() {
        sameAsSh("seq 1 20")
        sameAsSh("seq 3")
        sameAsSh("yes 'some text' | head -n 5")
    }

    @Test
    fun expandsTheVariablesOfTheEnvironment() {
        sameAsSh("echo \$HOME \"in \$HOME\" '\$HOME'")
    }

    @Test
    fun refusesWhatItDoesNotKnow() {
        assertTrue("not supported" in output(testProgram("i=0; while true; do echo; done")))
        assertTrue("not supported" in output(testProgram("echo \$(seq 1 3)")))
    }
}
