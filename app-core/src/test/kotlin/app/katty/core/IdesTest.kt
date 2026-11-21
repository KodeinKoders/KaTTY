package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdesTest {
    @Test
    fun onlyTheInstalledOnesAreFoundInTheOrderOfTheList() {
        val installed = setOf("com.apple.dt.Xcode", "com.microsoft.VSCode", "com.jetbrains.intellij")

        val found = detectIdes { it in installed }

        assertEquals(listOf(Ide.VS_CODE, Ide.INTELLIJ_IDEA, Ide.XCODE), found.keys.toList())
        assertEquals("com.jetbrains.intellij", found.getValue(Ide.INTELLIJ_IDEA))
    }

    @Test
    fun theFirstInstalledBundleOfAnIdeOpensIt() {
        val found = detectIdes { it == "org.eclipse.sdk.ide" || it == "org.eclipse.platform.ide" }

        assertEquals("org.eclipse.platform.ide", found.getValue(Ide.ECLIPSE))
    }

    @Test
    fun nothingIsFoundWhenNothingIsInstalledAndNotepadPlusPlusNeverIs() {
        assertTrue(detectIdes { false }.isEmpty())
        assertTrue(Ide.NOTEPAD_PLUS_PLUS !in detectIdes { true })
    }

    // A bundle that has every tool.
    private fun command(ide: Ide, line: Int? = null, column: Int? = null, bundle: String? = "/Applications/X.app", tools: Boolean = true) =
        openFileCommand(ide, "id.of.it", bundle, "/p/Foo.kt", line, column) { tools }

    @Test
    fun aJetBrainsIdeIsGivenTheLineAndTheColumnAsItsLauncherDoes() {
        assertEquals(listOf("open", "-n", "-b", "id.of.it", "--args", "--line", "12", "--column", "4", "/p/Foo.kt"), command(Ide.INTELLIJ_IDEA, 12, 5), "Its column counts from 0")
        assertEquals(listOf("open", "-n", "-b", "id.of.it", "--args", "--line", "12", "/p/Foo.kt"), command(Ide.ANDROID_STUDIO, 12))
        assertEquals(listOf("open", "-n", "-b", "id.of.it", "--args", "/p/Foo.kt"), command(Ide.RUSTROVER))
        assertEquals(command(Ide.CLION), command(Ide.CLION, bundle = null), "It needs no tool of its bundle")
    }

    @Test
    fun visualStudioCodeSublimeAndXcodeAreGivenItByTheToolsOfTheirBundle() {
        assertEquals(listOf("/Applications/X.app/Contents/Resources/app/bin/code", "--goto", "/p/Foo.kt:12:5"), command(Ide.VS_CODE, 12, 5))
        assertEquals(listOf("/Applications/X.app/Contents/Resources/app/bin/code", "/p/Foo.kt"), command(Ide.VS_CODE))
        assertEquals(listOf("/Applications/X.app/Contents/SharedSupport/bin/subl", "/p/Foo.kt:12"), command(Ide.SUBLIME_TEXT, 12))
        assertEquals(listOf("/Applications/X.app/Contents/Developer/usr/bin/xed", "--line", "12", "/p/Foo.kt"), command(Ide.XCODE, 12, 5))
        assertEquals(listOf("/Applications/X.app/Contents/Developer/usr/bin/xed", "/p/Foo.kt"), command(Ide.XCODE))
    }

    @Test
    fun openIdeaIsFoundBesideTheIntelliJIdeas() {
        val installed = setOf("com.google.android.studio", "ai.l0ng.openidea", "com.jetbrains.intellij")

        val found = detectIdes { it in installed }

        assertEquals(listOf(Ide.INTELLIJ_IDEA, Ide.OPENIDEA, Ide.ANDROID_STUDIO), found.keys.toList())
        assertEquals("OpenIDEA", Ide.OPENIDEA.displayName)
        assertTrue(Ide.OPENIDEA.forProjects)
    }

    @Test
    fun openIdeaIsGivenTheLineAndTheColumnByTheToolOfItsBundle() {
        val tool = "/Applications/X.app/Contents/Resources/bin/openidea"
        assertEquals(listOf(tool, "--line", "12", "--column", "5", "/p/Foo.kt"), command(Ide.OPENIDEA, 12, 5))
        assertEquals(listOf(tool, "--line", "12", "/p/Foo.kt"), command(Ide.OPENIDEA, 12))
        assertEquals(listOf(tool, "/p/Foo.kt"), command(Ide.OPENIDEA))
        assertEquals(listOf(tool, "/p/Foo.kt"), command(Ide.OPENIDEA, column = 5), "A column without a line is none")
        assertEquals(listOf("/X.app/Contents/Resources/bin/openidea", "/p/src"), openFileCommand(Ide.OPENIDEA, "id.of.it", "/X.app", "/p/src", null, null, directory = true) { true })
    }

    @Test
    fun withoutItsToolOrItsBundleTheFileIsOpenedAtItsStart() {
        val plain = listOf("open", "-b", "id.of.it", "/p/Foo.kt")
        assertEquals(plain, command(Ide.VS_CODE, 12, tools = false))
        assertEquals(plain, command(Ide.XCODE, 12, bundle = null))
        assertEquals(plain, command(Ide.OPENIDEA, 12, 5, tools = false))
        assertEquals(plain, command(Ide.OPENIDEA, 12, bundle = null))
        assertEquals(plain, command(Ide.ECLIPSE, 12, 5), "An IDE whose way to a line is not known")
    }

    @Test
    fun aFolderIsGivenAsItIs() {
        assertEquals(listOf("open", "-n", "-b", "id.of.it", "--args", "/p/src"), openFileCommand(Ide.INTELLIJ_IDEA, "id.of.it", null, "/p/src", null, null))
        assertEquals(listOf("/X.app/Contents/Resources/app/bin/code", "/p/src"), openFileCommand(Ide.VS_CODE, "id.of.it", "/X.app", "/p/src", null, null) { true })
    }

    @Test
    fun theFinderOpensAFolderAndShowsAFileSelectedInItsFolder() {
        fun finder(path: String, directory: Boolean) = openFileCommand(Ide.FINDER, "com.apple.finder", "/System/Library/CoreServices/Finder.app", path, 12, 5, directory)

        assertEquals(listOf("open", "-b", "com.apple.finder", "/p/src"), finder("/p/src", directory = true))
        assertEquals(listOf("open", "-R", "/p/src/Foo.kt"), finder("/p/src/Foo.kt", directory = false))
        assertEquals(listOf("open", "-R", "/p/Tool.app"), finder("/p/Tool.app", directory = true), "A bundle is shown, since opening it could run it")
    }

    @Test
    fun theFinderOpensOnlyWhatIsClicked() {
        assertEquals(listOf(Ide.FINDER), Ide.entries.filter { !it.forProjects })
        assertEquals(Ide.FINDER, detectIdes { it == "com.apple.finder" }.keys.single())
    }

    @Test
    fun aColumnWithoutALineIsNone() {
        assertEquals(listOf("/Applications/X.app/Contents/SharedSupport/bin/subl", "/p/Foo.kt"), command(Ide.SUBLIME_TEXT, column = 5))
    }

    @Test
    fun aPathThatIsNotAbsoluteIsRefused() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { openFileCommand(Ide.VS_CODE, "id", null, "-rf", 1, null) }
    }

    @Test
    fun anIdeIsReadByItsName() {
        assertEquals(Ide.XCODE, Ide.named("XCODE"))
        assertEquals(Ide.OPENIDEA, Ide.named("OPENIDEA"))
        assertEquals(null, Ide.named("ZED"))
        assertEquals(null, Ide.named(null))
    }

    @Test
    fun noBundleIdentifierIsGivenToTwoIdes() {
        val all = Ide.entries.flatMap { it.bundleIds }

        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun theIdesOfWindowsAreFoundByTheirExecutableAndTheirName() {
        val programs = listOf(
            InstalledProgram("IntelliJ IDEA Community Edition 2025.2", "C:\\Tools\\IC\\bin\\idea64.exe"),
            InstalledProgram("IntelliJ IDEA", "C:\\Users\\me\\AppData\\Local\\Programs\\IntelliJ IDEA\\bin\\idea64.exe"),
            InstalledProgram("Visual Studio Community 2026", "C:\\Program Files\\Microsoft Visual Studio\\18\\Community\\Common7\\IDE\\DEVENV.EXE"),
            InstalledProgram("Notepad++ (64-bit x64)", "C:\\Gone\\notepad++.exe"),
            InstalledProgram("Git", "C:\\Program Files\\Git\\git-bash.exe"),
        )
        val found = detectWindowsIdes(programs, onPath = { if (it == "Code.exe") "C:\\VS Code\\Code.exe" else null }, exists = { !it.startsWith("C:\\Gone") })

        assertEquals("C:\\Users\\me\\AppData\\Local\\Programs\\IntelliJ IDEA\\bin\\idea64.exe", found[Ide.INTELLIJ_IDEA])
        assertEquals("C:\\Tools\\IC\\bin\\idea64.exe", found[Ide.INTELLIJ_IDEA_CE], "The longest name wins")
        assertEquals("C:\\Program Files\\Microsoft Visual Studio\\18\\Community\\Common7\\IDE\\DEVENV.EXE", found[Ide.VISUAL_STUDIO], "Whatever the case")
        assertEquals("C:\\VS Code\\Code.exe", found[Ide.VS_CODE], "From the PATH")
        assertEquals("explorer.exe", found[Ide.FINDER])
        assertTrue(Ide.NOTEPAD_PLUS_PLUS !in found, "An executable that is gone")
        assertTrue(Ide.XCODE !in found)
        assertEquals(found.keys.toList(), Ide.entries.filter { it in found }, "In the order of Ide")
    }

    @Test
    fun aFileIsOpenedAtItsLineByEachIdeOfWindows() {
        val file = "C:\\p\\Foo.kt"
        fun command(ide: Ide, line: Int? = 12, column: Int? = 5, directory: Boolean = false) = windowsOpenFileCommand(ide, "X.exe", file, line, column, directory)

        assertEquals(listOf("X.exe", "--line", "12", "--column", "4", file), command(Ide.INTELLIJ_IDEA), "Its column counts from 0")
        assertEquals(listOf("X.exe", "--goto", "$file:12:5"), command(Ide.VS_CODE))
        assertEquals(listOf("X.exe", file), command(Ide.VS_CODE, line = null))
        assertEquals(listOf("X.exe", "$file:12:5"), command(Ide.SUBLIME_TEXT))
        assertEquals(listOf("X.exe", "-n12", "-c5", file), command(Ide.NOTEPAD_PLUS_PLUS))
        assertEquals(listOf("X.exe", "/edit", file, "/command", "Edit.GoTo 12"), command(Ide.VISUAL_STUDIO))
        assertEquals(listOf("X.exe", "/edit", file), command(Ide.VISUAL_STUDIO, line = null))
        assertEquals(listOf("X.exe", file), command(Ide.ECLIPSE))
        assertEquals(listOf("X.exe", "/select,", file), command(Ide.FINDER), "File Explorer shows a file, and opens nothing")
        assertEquals(listOf("X.exe", "C:\\p"), windowsOpenFileCommand(Ide.FINDER, "X.exe", "C:\\p", null, null, directory = true))
        assertEquals(listOf("X.exe", "C:\\p"), windowsOpenFileCommand(Ide.INTELLIJ_IDEA, "X.exe", "C:\\p", 3, null, directory = true), "A folder has no line")
        assertEquals(listOf("X.exe", "--line", "12", file), command(Ide.INTELLIJ_IDEA, column = null))
        assertFailsWith<IllegalArgumentException> { windowsOpenFileCommand(Ide.VS_CODE, "X.exe", "-rf", 1, null) }
    }
}
