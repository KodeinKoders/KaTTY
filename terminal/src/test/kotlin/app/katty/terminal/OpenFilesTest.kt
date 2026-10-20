package app.katty.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenFilesTest {
    @Test
    fun listsTheFilesAProcessHasOpen() {
        val file = Files.createTempFile("katty-open", ".lock").toRealPath()
        try {
            // The process is this one: the file is open while the channel is.
            java.nio.channels.FileChannel.open(file).use {
                assertTrue(file.toString() in openFilePaths(ProcessHandle.current().pid().toInt()))
            }
            assertFalse(file.toString() in openFilePaths(ProcessHandle.current().pid().toInt()), "A file that was closed is no longer open")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun aProcessThatIsGoneHasNoOpenFiles() {
        val gone = ProcessBuilder("/usr/bin/true").start().also { it.waitFor() }.pid().toInt()

        assertEquals(emptyList(), openFilePaths(gone))
    }

    @Test
    fun aProcessThatHoldsAFileIsToldFromOneThatDoesNot() {
        val file = Files.createTempFile("katty-held", ".lock").toRealPath()
        val holder = ProcessBuilder("/bin/sh", "-c", "exec 7<'$file'; sleep 30").start()
        try {
            val pid = holder.pid().toInt()
            val deadline = System.nanoTime() + 5_000_000_000
            while (file.toString() !in openFilePaths(pid) && System.nanoTime() < deadline) Thread.sleep(20)

            assertTrue(file.toString() in openFilePaths(pid), "The file held by the child process")
            assertFalse(file.toString() in openFilePaths(ProcessHandle.current().pid().toInt()))
        } finally {
            holder.destroyForcibly()
            Files.deleteIfExists(file)
        }
    }
}
