package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun noBundleIdentifierIsGivenToTwoIdes() {
        val all = Ide.entries.flatMap { it.bundleIds }

        assertEquals(all.size, all.toSet().size)
    }
}
