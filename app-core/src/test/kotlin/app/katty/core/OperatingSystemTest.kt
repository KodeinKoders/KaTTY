package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatingSystemTest {
    private fun folder(): Path = Files.createTempDirectory("katty-bin").also { it.toFile().deleteOnExit() }

    @Test
    fun aProgramOfWindowsIsFoundWithTheExtensionsOfPathExt() {
        val first = folder()
        val second = folder().also { Files.createFile(it.resolve("claude.cmd")) }
        Files.createDirectory(first.resolve("claude.exe"))
        val path = "$first;$second"

        assertEquals(second.resolve("claude.cmd"), findExecutable("claude", path, windows = true, pathExt = ".COM;.EXE;.CMD"), "A folder is no program")
        assertNull(findExecutable("claude", path, windows = true, pathExt = ".EXE"), "Only the extensions of PATHEXT")
        assertEquals(second.resolve("claude.cmd"), findExecutable("claude.cmd", path, windows = true, pathExt = ".EXE"), "A name with its extension is as it is")
        assertEquals(second.resolve("claude.cmd"), findExecutable("claude", path, windows = true, pathExt = null), "The extensions of Windows without PATHEXT")
    }

    @Test
    fun aProgramOfAUnixSystemIsAnExecutableFile() {
        org.junit.Assume.assumeTrue("POSIX permissions", !isWindows)
        val first = folder().also { Files.createFile(it.resolve("git")) }
        val second = folder().also { Files.createFile(it.resolve("git"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))) }

        assertEquals(second.resolve("git"), findExecutable("git", "$first:$second", windows = false), "A file that cannot be run is not the program")
        assertNull(findExecutable("git", "$first", windows = false))
    }

    @Test
    fun aProgramIsKnownByTheSameNameOnEverySystem() {
        assertEquals("vim", programName("/usr/bin/vim"))
        assertEquals("claude", programName("claude.exe"))
        assertEquals("script.sh", programName("script.sh"))
        if (isWindows) assertEquals("vim", programName("C:\\Tools\\vim.exe"))
    }

    @Test
    fun aProgramOfWindowsIsRecognizedAndStartedAgainAsOnAUnixSystem() {
        assertEquals(Agent.CLAUDE, Agent.fromCommand(listOf("claude.exe", "--model", "opus")))
        assertTrue(RestorePolicy().runs(listOf("vim.exe", "notes.txt")))
        assertFalse(RestorePolicy().runs(listOf("sudo.exe", "vim")))
    }
}
