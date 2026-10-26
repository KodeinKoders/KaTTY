package app.katty.screens

import app.katty.core.Ide
import app.katty.ui.MenuAction
import app.katty.ui.MenuSubmenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NavigationMenusTest {
    private fun menu(ides: List<Ide>, opened: MutableList<Ide> = mutableListOf()) =
        projectMenu(onNewWorkspace = {}, onRename = {}, onReveal = {}, onRemove = {}, ides = ides, onOpenIn = { opened += it })

    @Test
    fun theAddProjectMenuOffersTheFolderChooserAndTheRecentProjects() {
        val calls = mutableListOf<String>()
        val actions = addProjectMenu({ calls += "add" }, { calls += "recent" }).filterIsInstance<MenuAction>()

        assertEquals(listOf("Add project folder…", "Open recent project…"), actions.map { it.label })
        actions.forEach { it.onClick() }
        assertEquals(listOf("add", "recent"), calls)
    }

    @Test
    fun theProjectMenuHasNoOpenInWhenNoEditorIsInstalled() {
        assertNull(menu(emptyList()).filterIsInstance<MenuSubmenu>().firstOrNull())
    }

    @Test
    fun theProjectMenuOpensTheFolderInTheEditorsThatAreInstalled() {
        val opened = mutableListOf<Ide>()
        val submenu = assertNotNull(menu(listOf(Ide.VS_CODE, Ide.XCODE), opened).filterIsInstance<MenuSubmenu>().singleOrNull())

        assertEquals("Open in", submenu.label)
        assertEquals(listOf("Visual Studio Code", "Xcode"), submenu.entries.filterIsInstance<MenuAction>().map { it.label })
        submenu.entries.filterIsInstance<MenuAction>().last().onClick()
        assertEquals(listOf(Ide.XCODE), opened)
    }
}
