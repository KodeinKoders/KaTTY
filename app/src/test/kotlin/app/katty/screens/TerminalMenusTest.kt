package app.katty.screens

import app.katty.core.WorkspaceId
import app.katty.ui.MenuAction
import app.katty.ui.MenuDivider
import app.katty.ui.MenuEntry
import app.katty.ui.MenuSubmenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalMenusTest {
    private val calls = mutableListOf<String>()

    private val menuActions = TerminalMenuActions(
        rename = { calls += "rename" },
        splitRight = { calls += "splitRight" },
        splitDown = { calls += "splitDown" },
        moveTo = { calls += "move ${it.value}" },
        colorScheme = { calls += "colorScheme" },
        copyDirectory = { calls += "copyDirectory" },
        restart = { calls += "restart" },
        close = { calls += "close" },
    )

    private val contextActions = ContextMenuActions(
        copy = { calls += "copy" },
        paste = { calls += "paste" },
        selectAll = { calls += "selectAll" },
        find = { calls += "find" },
        clearScreen = { calls += "clear" },
        splitRight = { calls += "splitRight" },
        splitDown = { calls += "splitDown" },
        close = { calls += "close" },
    )

    private fun List<MenuEntry>.labels(): List<String> = map {
        when (it) {
            is MenuAction -> it.label
            is MenuSubmenu -> "${it.label} ›"
            MenuDivider -> "---"
        }
    }

    private fun List<MenuEntry>.action(label: String) = filterIsInstance<MenuAction>().first { it.label == label }

    @Test
    fun theMenuOfAPaneIsTheDesignsInItsOrder() {
        val entries = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w"), "release-notes")), "Default", menuActions)

        assertEquals(
            listOf("Rename terminal", "---", "Split right", "Split down", "---", "Move to workspace ›", "Color scheme", "---", "Copy working directory", "---", "Restart terminal", "Close terminal"),
            entries.labels(),
        )
    }

    @Test
    fun restartingATerminalShowsItsShortcut() {
        val restart = terminalMenu(emptyList(), "Default", menuActions).action("Restart terminal")

        assertEquals(app.katty.AppShortcut.RESTART_TERMINAL.label, restart.shortcut)
    }

    @Test
    fun closingATerminalIsRedAndNothingElseIs() {
        val entries = terminalMenu(emptyList(), "Default", menuActions)

        assertEquals(listOf("Close terminal"), entries.filterIsInstance<MenuAction>().filter { it.danger }.map { it.label })
    }

    @Test
    fun movingGoesToTheWorkspaceThatWasChosen() {
        val entries = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w1"), "a"), WorkspaceChoice(WorkspaceId("w2"), "b")), "Default", menuActions)

        val submenu = entries.filterIsInstance<MenuSubmenu>().single()
        assertEquals(listOf("a", "b"), submenu.entries.labels())
        (submenu.entries[1] as MenuAction).onClick()
        assertEquals(listOf("move w2"), calls)
    }

    @Test
    fun onlyTheOtherWorkspacesOfTheSameProjectAreOffered() {
        var counter = 0
        val store = app.katty.core.KattyStore(newId = { "id${counter++}" })
        store.addProject("/dev/a")
        val terminal = store.state.value.selection.terminal!!
        val second = store.addWorkspace(store.state.value.selection.project!!, "second")!!
        store.addProject("/dev/b")

        // The terminal of the first project: its other workspace, and nothing of the second project.
        assertEquals(listOf(second), destinationsOf(store.state.value, terminal).map { it.id })
        assertEquals(listOf("second"), destinationsOf(store.state.value, terminal).map { it.label })
        // A project with one workspace has nowhere to move to.
        assertEquals(emptyList(), destinationsOf(store.state.value, store.state.value.selection.terminal!!))
    }

    @Test
    fun withNoOtherWorkspaceThereIsNothingToMoveTo() {
        val entries = terminalMenu(emptyList(), "Default", menuActions)

        assertFalse(entries.any { it is MenuSubmenu })
        assertEquals(listOf("Rename terminal", "---", "Split right", "Split down", "---", "Color scheme", "---", "Copy working directory", "---", "Restart terminal", "Close terminal"), entries.labels())
    }

    @Test
    fun theItemsOfTheMenuDoWhatTheySay() {
        val entries = terminalMenu(emptyList(), "Default", menuActions)

        listOf("Rename terminal", "Split right", "Split down", "Copy working directory", "Restart terminal", "Close terminal").forEach { entries.action(it).onClick() }

        assertEquals(listOf("rename", "splitRight", "splitDown", "copyDirectory", "restart", "close"), calls)
    }

    @Test
    fun aTerminalWithANameOfItsOwnCanGoBackToTheNameOfItsProcess() {
        val withName = TerminalMenuActions(
            rename = {}, useProcessName = { calls += "revert" }, splitRight = {}, splitDown = {}, moveTo = {}, colorScheme = {}, copyDirectory = {}, restart = {}, close = {},
        )

        val entries = terminalMenu(emptyList(), "Default", withName)

        // Right after Rename terminal.
        assertEquals(listOf("Rename terminal", "Use process name", "---"), entries.labels().take(3))
        entries.action("Use process name").onClick()
        assertEquals(listOf("revert"), calls)
    }

    @Test
    fun aTerminalWithoutOneHasNothingToGiveUp() {
        assertFalse(terminalMenu(emptyList(), "Default", menuActions).labels().contains("Use process name"))
    }

    @Test
    fun theColorSchemeItemSaysWhatTheSchemeIsNow() {
        val item = terminalMenu(emptyList(), "Nord", menuActions).action("Color scheme")

        assertEquals("Nord", item.hint)
        item.onClick()
        assertEquals(listOf("colorScheme"), calls)
    }

    @Test
    fun theContextMenuIsTheDesignsInItsOrder() {
        val entries = terminalContextMenu(hasSelection = true, actions = contextActions)

        assertEquals(
            listOf("Copy", "Paste", "Select all", "---", "Find…", "Clear screen and scrollback", "---", "Split right", "Split down", "---", "Close terminal"),
            entries.labels(),
        )
    }

    @Test
    fun theSplitShortcutsFollowTheLayout() {
        fun shortcuts(entries: List<MenuEntry>) = entries.filterIsInstance<MenuAction>().associate { it.label to it.shortcut }

        assertEquals("⌘D", shortcuts(terminalMenu(emptyList(), "Default", menuActions))["Split right"])
        assertEquals("⌘⇧D", shortcuts(terminalMenu(emptyList(), "Default", menuActions))["Split down"])
        assertEquals("⌘⇧D", shortcuts(terminalMenu(emptyList(), "Default", menuActions, vertical = true))["Split right"])
        assertEquals("⌘D", shortcuts(terminalMenu(emptyList(), "Default", menuActions, vertical = true))["Split down"])
        assertEquals("⌘⇧D", shortcuts(terminalContextMenu(true, contextActions, vertical = true))["Split right"])
        assertEquals("⌘D", shortcuts(terminalContextMenu(true, contextActions, vertical = true))["Split down"])
        assertEquals("⌘F2", shortcuts(terminalMenu(emptyList(), "Default", menuActions))["Rename terminal"])
    }

    @Test
    fun copyIsOnlyForWhatIsSelected() {
        assertTrue(terminalContextMenu(true, contextActions).action("Copy").enabled)
        assertFalse(terminalContextMenu(false, contextActions).action("Copy").enabled)
    }

    @Test
    fun theShortcutsAreTheOnesTheWindowHandles() {
        val entries = terminalContextMenu(true, contextActions)

        assertEquals(
            mapOf("Copy" to "⌘C", "Paste" to "⌘V", "Select all" to "⌘A", "Find…" to "⌘F", "Clear screen and scrollback" to "⌘L", "Split right" to "⌘D", "Split down" to "⌘⇧D", "Close terminal" to "⌘W"),
            entries.filterIsInstance<MenuAction>().associate { it.label to it.shortcut },
        )
    }
}
