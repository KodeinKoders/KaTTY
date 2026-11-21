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
    fun theWorkspaceMenuChoosesItsIdeWhenOneIsInstalled() {
        assertEquals(listOf("Rename workspace", "Close workspace"), workspaceMenu({}, {}).filterIsInstance<MenuAction>().map { it.label })

        var chosen = false
        val actions = workspaceMenu({}, {}, onDefaultIde = { chosen = true }).filterIsInstance<MenuAction>()
        assertEquals(listOf("Rename workspace", "Default IDE…", "Close workspace"), actions.map { it.label })
        actions[1].onClick()
        assertEquals(true, chosen)
    }

    @Test
    fun theEnvironmentOfAProjectIsChosenInItsMenuTheOneInUseChecked() {
        val chosen = mutableListOf<String?>()
        var opened = 0
        val environment = EnvironmentMenu("kodein", setOf("kodein", "perso"), { chosen += it }, { opened++ })
        val submenu = projectMenu({}, {}, {}, {}, environment = environment).filterIsInstance<MenuSubmenu>().single()

        assertEquals("Environment", submenu.label)
        assertEquals("kodein", submenu.hint)
        val items = submenu.entries.filterIsInstance<MenuAction>()
        assertEquals(listOf("None", "kodein", "perso", "Open environments folder"), items.map { it.label })
        assertEquals(listOf(false, true, false, null), items.map { it.checked })
        items[0].onClick()
        items[2].onClick()
        items[3].onClick()
        assertEquals(listOf(null, "perso"), chosen)
        assertEquals(1, opened)
    }

    @Test
    fun anEnvironmentThatHasNoFileSaysSoInTheMenu() {
        val submenu = projectMenu({}, {}, {}, {}, environment = EnvironmentMenu("sunday", setOf("kodein"), {}, {})).filterIsInstance<MenuSubmenu>().single()

        assertEquals(listOf("None", "kodein", "sunday (not found)", "Open environments folder"), submenu.entries.filterIsInstance<MenuAction>().map { it.label })
    }

    @Test
    fun theWorkspaceMenuOffersTheEnvironmentOfItsProjectOnlyWhenItHasAnother() {
        var used = 0
        fun labels(workspace: String?, project: String?) =
            workspaceMenu({}, {}, environment = EnvironmentMenu(workspace, setOf("kodein", "perso"), {}, {}), projectEnvironment = project, onUseProjectEnvironment = { used++ })
                .filterIsInstance<MenuAction>()

        assertEquals(listOf("Rename workspace", "Close workspace"), labels("kodein", "kodein").map { it.label })
        val offered = labels("perso", "kodein").first { it.label == "Use project environment" }
        assertEquals("kodein", offered.hint)
        offered.onClick()
        assertEquals(1, used)
        assertEquals("None", labels("perso", null).first { it.label == "Use project environment" }.hint)
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
