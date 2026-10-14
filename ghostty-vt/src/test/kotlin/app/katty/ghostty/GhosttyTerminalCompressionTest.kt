package app.katty.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GhosttyTerminalCompressionTest {
    private fun fill(terminal: GhosttyTerminal, lines: Int) = repeat(lines) { terminal.vtWrite("line number $it with some text to take room\r\n") }

    @Test
    fun theHistoryReadsTheSameAfterItWasCompressed() {
        GhosttyTerminal(cols = 80, rows = 24, scrollbackMaxBytes = 100_000_000).use { terminal ->
            fill(terminal, 60_000)
            val before = terminal.readRows(0, 59_999)
            val usageBefore = terminal.memoryUsage()

            assertEquals(GhosttyCompressionResult.COMPLETE, terminal.compress(GhosttyCompressionMode.FULL))

            val usage = terminal.memoryUsage()
            if (!usage.compressionSupported) return
            assertTrue(usage.compressedPages > 0, "Pages were compressed: $usage")
            assertTrue(usage.residentBytes < usageBefore.residentBytes, "It takes less: ${usageBefore.residentBytes} then ${usage.residentBytes}")
            assertEquals(before, terminal.readRows(0, 59_999), "Reading decompresses it: nothing changed")
        }
    }

    @Test
    fun anIncrementalPassGetsThereInSteps() {
        GhosttyTerminal(cols = 80, rows = 24, scrollbackMaxBytes = 100_000_000).use { terminal ->
            fill(terminal, 60_000)
            var result = GhosttyCompressionResult.PENDING
            var steps = 0
            while (result == GhosttyCompressionResult.PENDING && steps < 100_000) {
                result = terminal.compress(GhosttyCompressionMode.INCREMENTAL)
                steps++
            }

            assertTrue(result != GhosttyCompressionResult.PENDING, "It ended after $steps steps")
        }
    }

    @Test
    fun theActivityChangesWhenTheTerminalDoes() {
        GhosttyTerminal(cols = 80, rows = 24).use { terminal ->
            val before = terminal.compressionActivity()
            assertEquals(before, terminal.compressionActivity(), "Reading it changes nothing")

            // The history grows: that is a change that compressing cares about.
            fill(terminal, 30_000)

            assertNotEquals(before, terminal.compressionActivity())
        }
    }

    @Test
    fun theMemoryUsageHasWhatTheTerminalHolds() {
        GhosttyTerminal(cols = 80, rows = 24, scrollbackMaxBytes = 100_000_000).use { terminal ->
            val empty = terminal.memoryUsage()
            fill(terminal, 30_000)

            val full = terminal.memoryUsage()

            assertTrue(full.residentBytes > empty.residentBytes, "The history takes room: ${empty.residentBytes} then ${full.residentBytes}")
            assertEquals(0, full.imageBytes)
        }
    }
}
