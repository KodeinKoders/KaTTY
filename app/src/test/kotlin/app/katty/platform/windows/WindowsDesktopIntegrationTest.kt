package app.katty.platform.windows

import app.katty.core.Ide
import app.katty.core.isWindows
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsDesktopIntegrationTest {
    @Test
    fun theExecutableOfAnIconIsItsPathWithoutQuotesNorIndex() {
        assertEquals("C:\\Program Files\\X\\x.exe", iconExecutable("\"C:\\Program Files\\X\\x.exe\",0"))
        assertEquals("C:\\Users\\me\\IntelliJ IDEA\\bin\\idea64.exe", iconExecutable("C:\\Users\\me\\IntelliJ IDEA\\bin\\idea64.exe"))
        assertEquals("C:\\X\\msedge.exe", iconExecutable("C:\\X\\msedge.exe,0"))
        assertEquals("C:\\X\\x.EXE", iconExecutable("C:\\X\\x.EXE,-101"))
        assertNull(iconExecutable("C:\\X\\git-for-windows.ico"), "An icon of its own")
        assertNull(iconExecutable(""))
    }

    @Test
    fun theIdesOfThisWindowsAreFoundInItsRegistry() {
        assumeTrue("Windows", isWindows)
        val programs = installedPrograms()
        assertTrue(programs.isNotEmpty(), "The registry lists programs")

        // What the VM that this was written on has: IntelliJ IDEA from JetBrains Toolbox, Visual Studio, VS Code and Notepad++ from winget.
        val ides = WindowsDesktopIntegration.installedIdes()
        println("Installed IDEs: $ides")
        assertEquals("explorer.exe", ides[Ide.FINDER])
        assertTrue(ides.values.all { it == "explorer.exe" || java.io.File(it).isFile }, "$ides")
    }
}
