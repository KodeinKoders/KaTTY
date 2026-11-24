package app.katty.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellsTest {
    private val zsh = ShellLaunch(listOf("/bin/zsh", "-l"), emptyMap(), "zsh", "zsh", isDefault = true)

    @Test
    fun theShellsOfAUnixSystemAreFoundOnceEachAndToldApartByTheirFolder() {
        val executables = setOf("/bin/zsh", "/bin/bash", "/usr/bin/zsh", "/opt/homebrew/bin/bash", "/opt/homebrew/bin/fish", "/usr/local/bin/pwsh")
        val shells = detectUnixShells(
            listOf("/bin", "/usr/bin", "/opt/homebrew/bin"),
            etcShells = listOf("# comment", "/bin/zsh", "/usr/local/bin/pwsh", "/bin/tcsh"),
            isExecutable = { it in executables },
            // /usr/bin/zsh is a link to /bin/zsh: one shell, found first in /bin.
            canonical = { if (it == "/usr/bin/zsh") "/bin/zsh" else it },
        )

        assertEquals(listOf("/bin/zsh", "/bin/bash", "/opt/homebrew/bin/bash", "/opt/homebrew/bin/fish", "/usr/local/bin/pwsh"), shells.map { it.id })
        assertEquals(listOf("zsh", "bash (/bin)", "bash (/opt/homebrew/bin)", "fish", "pwsh"), shells.map { it.label })
        assertEquals(listOf("/bin/zsh", "-l"), shells.first().argv)
        assertEquals(listOf("/usr/local/bin/pwsh", "-Login", "-NoLogo"), shells.last().argv, "-Login must be the first parameter of pwsh")
    }

    private val windowsFiles = setOf(
        "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
        "C:\\Program Files\\Git\\usr\\bin\\bash.exe", "C:\\Program Files\\Git\\clangarm64",
        "C:\\msys64\\usr\\bin\\bash.exe", "C:\\msys64\\usr\\bin\\zsh.exe",
        "C:\\cygwin64\\bin\\bash.exe", "C:\\cygwin64\\bin\\fish.exe",
        "C:\\Windows\\System32\\cmd.exe", "C:\\Windows\\System32\\wsl.exe",
    )

    private fun windowsShells(inherited: Map<String, String> = emptyMap(), pwsh: String? = null) = detectWindowsShells(
        WindowsShellRoots(git = listOf("C:\\Program Files\\Git"), msys2 = listOf("C:\\msys64"), cygwin = listOf("C:\\cygwin64", "D:\\cygwin")),
        exists = { it in windowsFiles }, pwsh = pwsh, systemRoot = "C:\\Windows", programFiles = "C:\\Program Files", inherited = inherited,
    )

    @Test
    fun theShellsOfWindowsArePowerShellAndTheShellsOfItsPosixEnvironments() {
        val shells = windowsShells()

        assertEquals(
            listOf("PowerShell 7", "Windows PowerShell", "Git Bash", "MSYS2 bash", "MSYS2 zsh", "Cygwin bash", "Cygwin fish"),
            shells.map { it.label },
            "Neither cmd nor WSL",
        )
        val git = shells.single { it.label == "Git Bash" }
        assertEquals(listOf("C:\\Program Files\\Git\\usr\\bin\\bash.exe", "--login", "-i"), git.argv, "Its bash itself, not the launcher of bin")
        assertEquals(mapOf("MSYSTEM" to "CLANGARM64"), git.environment, "The toolchain that the install has")
        val msys = shells.single { it.label == "MSYS2 zsh" }
        assertEquals("zsh", msys.name)
        assertEquals(mapOf("MSYSTEM" to "UCRT64", "MSYS2_PATH_TYPE" to "inherit"), msys.environment, "The PATH of Windows, the user's choice")
        assertEquals(emptyMap(), shells.single { it.label == "Cygwin fish" }.environment)
    }

    @Test
    fun aPathTypeOfMsys2ThatTheUserSetIsKeptAndPwshIsFoundOnThePathFirst() {
        val shells = windowsShells(inherited = mapOf("MSYS2_PATH_TYPE" to "strict"), pwsh = "C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\pwsh.exe")

        assertEquals(mapOf("MSYSTEM" to "UCRT64"), shells.single { it.label == "MSYS2 bash" }.environment)
        assertEquals("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\pwsh.exe", shells.first().id)
    }

    @Test
    fun aCommandLineOfWindowsIsSplitAsWindowsSplitsItAfterItsVariables() {
        val launch = parseShellCommand("MSYSTEM=UCRT64 \"C:\\Program Files\\msys\\usr\\bin\\zsh.exe\" --login -i", windows = true)!!

        assertEquals(listOf("C:\\Program Files\\msys\\usr\\bin\\zsh.exe", "--login", "-i"), launch.argv)
        assertEquals(mapOf("MSYSTEM" to "UCRT64"), launch.environment)
        assertEquals("zsh", launch.name)
        assertEquals(false, launch.isDefault)
    }

    @Test
    fun aCommandLineOfUnixIsSplitAsAShellSplitsIt() {
        val launch = parseShellCommand("FOO='a b' ~/bin/fish -l 'it''s' \"c \\\"d\\\"\" e\\ f", windows = false, home = "/Users/me")!!

        assertEquals(listOf("/Users/me/bin/fish", "-l", "its", "c \"d\"", "e f"), launch.argv)
        assertEquals(mapOf("FOO" to "a b"), launch.environment)
    }

    @Test
    fun aProgramWithoutAFolderIsLookedForAndABlankLineIsNone() {
        assertEquals("/opt/homebrew/bin/fish", parseShellCommand("fish -l", windows = false, find = { if (it == "fish") "/opt/homebrew/bin/fish" else null })!!.argv[0])
        assertEquals("nushell", parseShellCommand("nushell", windows = false)!!.argv[0], "Kept as it is typed when it is not found")
        assertNull(parseShellCommand("   ", windows = false))
        assertNull(parseShellCommand("A=1", windows = false), "Variables and no program")
    }

    @Test
    fun theSettingIsTheDefaultAnInstalledShellOrTheCommandAndAShellThatIsGoneIsTheDefault() {
        val installed = listOf(InstalledShell("/opt/homebrew/bin/fish", "fish", "fish", listOf("/opt/homebrew/bin/fish", "-l")))
        val missing = mutableListOf<String>()
        fun resolve(shell: String?, command: String = "") = resolveShell(shell, command, installed, zsh, windows = false, missing = { missing += it })

        assertEquals(zsh, resolve(null))
        assertEquals(listOf("/opt/homebrew/bin/fish", "-l"), resolve("/opt/homebrew/bin/fish").argv)
        assertEquals(listOf("/bin/bash", "-l"), resolve(CUSTOM_SHELL, "/bin/bash -l").argv)
        assertEquals(zsh, resolve(CUSTOM_SHELL, "  "), "A blank command is the default")
        assertEquals(zsh, resolve("/usr/local/bin/fish"), "Not installed any more")
        assertEquals(listOf("/usr/local/bin/fish"), missing)
    }

    @Test
    fun anInstalledShellOfWindowsIsFoundWhateverTheCaseOfItsPath() {
        val installed = listOf(InstalledShell("C:\\msys64\\usr\\bin\\bash.exe", "bash", "MSYS2 bash", listOf("C:\\msys64\\usr\\bin\\bash.exe", "--login", "-i")))

        assertEquals("MSYS2 bash", resolveShell("c:\\MSYS64\\usr\\bin\\bash.exe", "", installed, zsh, windows = true).label)
    }

    @Test
    fun aRunTerminalRunsItsCommandWithTheShellWhenItKnowsHow() {
        val pwsh = ShellLaunch(listOf("pwsh.exe", "-NoLogo"), emptyMap(), "pwsh", "PowerShell 7", isDefault = true)
        val msys = ShellLaunch(listOf("C:\\msys64\\usr\\bin\\bash.exe", "--login", "-i"), mapOf("MSYSTEM" to "UCRT64"), "bash", "MSYS2 bash", isDefault = false)
        val nushell = ShellLaunch(listOf("nu"), emptyMap(), "nu", "nu", isDefault = false)
        val sh = ShellLaunch(listOf("/bin/sh", "-l"), emptyMap(), "sh", "sh", isDefault = true)

        assertEquals(listOf("pwsh.exe", "-NoLogo", "-Command", "make"), runArgv(pwsh, "make", pwsh, windows = true).argv)
        val run = runArgv(msys, "make", pwsh, windows = true)
        assertEquals(listOf("C:\\msys64\\usr\\bin\\bash.exe", "--login", "-i", "-c", "make"), run.argv)
        assertEquals(mapOf("MSYSTEM" to "UCRT64"), run.environment)
        assertEquals(listOf("/bin/zsh", "-l", "-i", "-c", "make"), runArgv(zsh, "make", zsh, windows = false).argv)
        assertEquals(listOf("pwsh.exe", "-NoLogo", "-Command", "make"), runArgv(nushell, "make", pwsh, windows = true).argv, "A shell that KaTTY does not know: the default runs it")
        assertEquals(listOf("/bin/sh", "-l", "-i", "-c", "make"), runArgv(sh, "make", sh, windows = false).argv, "The default as it always did")
    }

    @Test
    fun settingsWrittenBeforeTheShellCouldBeChosenStartTheDefaultOne() {
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString(Settings.serializer(), """{"fontSize": 15.0}""")

        assertNull(settings.shell)
        assertEquals("", settings.shellCommand)
        assertTrue(resolveShell(settings.shell, settings.shellCommand, emptyList(), zsh).isDefault)
    }
}
