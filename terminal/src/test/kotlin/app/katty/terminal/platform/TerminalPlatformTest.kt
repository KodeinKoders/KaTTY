package app.katty.terminal.platform

import app.katty.terminal.platform.macos.MacosTerminalPlatform
import com.pty4j.PtyProcessBuilder
import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalPlatformTest {
    @Test
    fun macOSHasItsOwn() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        assertSame(MacosTerminalPlatform, terminalPlatform)
    }

    @Test
    fun anUnknownSystemKnowsNothingAndStillEndsAProgram() {
        // A program that waits, on whatever system runs the test: the unknown platform is what a system without its own gets.
        val waits = if (System.getProperty("os.name").startsWith("Windows")) arrayOf("cmd.exe", "/c", "ping -n 30 127.0.0.1 > nul") else arrayOf("/bin/sh", "-c", "sleep 30")
        // In ConPTY, as the sessions start their programs: pty4j's winpty took more than 5 s to end one once, with other tests running.
        val process = PtyProcessBuilder(waits).setConsole(false).setUseWinConPty(true).start()
        try {
            val platform = UnknownTerminalPlatform
            assertFalse(platform.pty.readingPassword(process))
            assertFalse(platform.pty.setWindowSize(process, 80, 24, 640, 384))
            assertNull(platform.processes.foregroundProcessName(process))
            assertNull(platform.processes.foregroundCommand(process))
            assertEquals(emptyList(), platform.processes.sessionPids(process.pid().toInt()))
            assertEquals(emptyList(), platform.processes.openFilePaths(process.pid().toInt()))
            assertNull(platform.hostName())

            platform.pty.hangUp(process)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The program ended")
            platform.pty.closePty(process)
        } finally {
            process.destroyForcibly()
        }
    }
}
