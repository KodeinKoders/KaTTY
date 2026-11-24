package app.katty.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The paths of the POSIX environments of Windows (MSYS2, Git for Windows, Cygwin), which their shells write their way. Pure: it runs everywhere. */
class PosixShellsTest {
    private val msys = PosixFileSystem("C:\\msys64", "/", listOf("/bin" to "C:\\msys64\\usr\\bin"))
    private val cygwin = PosixFileSystem("C:\\cygwin64", "/cygdrive/")

    @Test
    fun aPathOfWindowsIsWrittenAsTheShellWritesIt() {
        assertEquals("/c/Users/me/a b", msys.toPosix("C:\\Users\\me\\a b"))
        assertEquals("/cygdrive/d/work", cygwin.toPosix("D:\\work"))
        assertEquals("/c", msys.toPosix("C:"))
        assertEquals("//server/share/x", msys.toPosix("\\\\server\\share\\x"))
    }

    @Test
    fun aPathOfTheShellIsReadAsWindowsWritesIt() {
        assertEquals("C:\\Users\\me\\a b", msys.toWindows("/c/Users/me/a b"))
        assertEquals("C:\\", msys.toWindows("/c"))
        assertEquals("D:\\work", cygwin.toWindows("/cygdrive/d/work"))
        assertEquals("C:\\msys64\\home\\me", msys.toWindows("/home/me"), "Under the root of the environment")
        assertEquals("C:\\msys64\\usr\\bin\\vim", msys.toWindows("/bin/vim"), "A folder that it mounts")
        assertEquals("C:\\cygwin64\\cygdrive", cygwin.toWindows("/cygdrive"), "Its drives' folder itself is a folder of its root")
        assertEquals("\\\\server\\share", msys.toWindows("//server/share"))
        assertNull(msys.toWindows("relative/path"))
    }

    // What the folders of the environments have, by their paths.
    private val files = setOf(
        "C:\\msys64\\usr\\bin\\msys-2.0.dll",
        "C:\\Program Files\\Git\\usr\\bin\\msys-2.0.dll",
        "C:\\Program Files\\Git\\bin\\..\\usr\\bin\\msys-2.0.dll",
        "C:\\cygwin64\\bin\\cygwin1.dll",
    )

    private val fstabs = mapOf(
        "C:\\Program Files\\Git\\etc\\fstab" to listOf("# Git for Windows", "none / cygdrive binary,posix=0,noacl,user 0 0", "none /tmp usertemp binary,posix=0,noacl 0 0"),
        "C:\\cygwin64\\etc\\fstab" to listOf("none /mnt cygdrive binary,posix=0,user 0 0", "D:/my\\040work /work ntfs binary 0 0"),
    )

    private fun of(program: String) = PosixFileSystem.of(program, exists = { it in files }, read = { fstabs[it] }, temporary = "C:\\Users\\me\\AppData\\Local\\Temp")

    @Test
    fun theEnvironmentOfAShellIsKnownByTheLibraryNextToIt() {
        val msys2 = of("C:\\msys64\\usr\\bin\\zsh.exe")!!
        assertEquals("C:\\msys64", msys2.root)
        assertEquals("/", msys2.cygdrive)

        val git = of("C:\\Program Files\\Git\\usr\\bin\\bash.exe")!!
        assertEquals("C:\\Program Files\\Git", git.root)
        assertEquals("C:\\Users\\me\\AppData\\Local\\Temp\\x", git.toWindows("/tmp/x"), "Git's /tmp is the user's temporary folder")
        assertEquals("C:\\Program Files\\Git", of("C:\\Program Files\\Git\\bin\\bash.exe")!!.root, "Its launcher, whose library is in usr\\bin")

        val cyg = of("C:\\cygwin64\\bin\\bash.exe")!!
        assertEquals("C:\\cygwin64", cyg.root)
        assertEquals("/mnt/", cyg.cygdrive, "Its fstab moved the drives")
        assertEquals("C:\\x", cyg.toWindows("/mnt/c/x"))
        assertEquals("D:\\my work\\a", cyg.toWindows("/work/a"), "A folder that its fstab mounts")

        assertNull(of("C:\\Windows\\System32\\bash.exe"), "WSL's bash belongs to none")
        assertNull(of("bash.exe"))
    }

    @Test
    fun aDirectoryThatSuchAShellReportsIsItsWindowsPath() {
        val local = { host: String -> host == "PC" }

        assertEquals("C:\\Users\\me\\a b", decodeWorkingDirectory("file://PC/c/Users/me/a%20b", local, windows = true, posix = msys))
        assertEquals("C:\\cygwin64\\home\\me", decodeWorkingDirectory("file://PC/home/me", local, windows = true, posix = cygwin))
        assertEquals("C:\\Users\\me", decodeWorkingDirectory("file://PC/C:/Users/me", local, windows = true, posix = msys), "PowerShell's way still")
        assertEquals("/c/Users/me", decodeWorkingDirectory("file://PC/c/Users/me", local, windows = false, posix = null), "A folder of a Unix system")
    }

    @Test
    fun onWindowsAPosixShellIsIntegratedOnlyInAnEnvironmentAndGivenItsPaths() {
        val directory = java.nio.file.Files.createTempDirectory("katty integration").also { it.toFile().deleteOnExit() }

        val environment = HashMap<String, String>()
        val wsl = ShellIntegration.apply(listOf("C:\\Windows\\System32\\bash.exe", "-l"), environment, directory, windows = true)
        assertFalse(wsl.installed, "A bash of no environment it knows is started as it is")
        assertTrue(environment.isEmpty(), "$environment")

        val bash = ShellIntegration.apply(listOf("C:\\msys64\\usr\\bin\\bash.exe", "--login", "-i"), environment, directory, posix = msys, windows = true)
        assertTrue(bash.installed)
        assertEquals(msys.toPosix(directory.resolve("bash/katty.bash").toString()), bash.argv[2], "The script as the shell writes its path")
        assertEquals("1", environment["CHERE_INVOKING"], "It starts in the terminal's directory, not the home")

        val zshEnvironment = hashMapOf("CHERE_INVOKING" to "0")
        ShellIntegration.apply(listOf("C:\\cygwin64\\bin\\zsh.exe", "--login", "-i"), zshEnvironment, directory, posix = cygwin, windows = true)
        assertEquals(cygwin.toPosix(directory.resolve("zsh").toString()), zshEnvironment["ZDOTDIR"])
        assertEquals("0", zshEnvironment["CHERE_INVOKING"], "What the user set is kept")
    }
}
