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
        copyDirectory = { calls += "copyDirectory" },
        close = { calls += "close" },
    )

    private val contextActions = ContextMenuActions(
        copy = { calls += "copy" },
        paste = { calls += "paste" },
        selectAll = { calls += "selectAll" },
        clearScrollback = { calls += "clear" },
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
        val entries = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w"), "release-notes")), menuActions)

        assertEquals(
            listOf("Rename terminal", "---", "Split right", "Split down", "---", "Move to workspace ›", "---", "Copy working directory", "---", "Close terminal"),
            entries.labels(),
        )
    }

    @Test
    fun closingATerminalIsRedAndNothingElseIs() {
        val entries = terminalMenu(emptyList(), menuActions)

        assertEquals(listOf("Close terminal"), entries.filterIsInstance<MenuAction>().filter { it.danger }.map { it.label })
    }

    @Test
    fun movingGoesToTheWorkspaceThatWasChosen() {
        val entries = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w1"), "a"), WorkspaceChoice(WorkspaceId("w2"), "api / b")), menuActions)

        val submenu = entries.filterIsInstance<MenuSubmenu>().single()
        assertEquals(listOf("a", "api / b"), submenu.entries.labels())
        (submenu.entries[1] as MenuAction).onClick()
        assertEquals(listOf("move w2"), calls)
    }

    @Test
    fun withNoOtherWorkspaceThereIsNothingToMoveTo() {
        val entries = terminalMenu(emptyList(), menuActions)

        assertFalse(entries.any { it is MenuSubmenu })
        assertEquals(listOf("Rename terminal", "---", "Split right", "Split down", "---", "Copy working directory", "---", "Close terminal"), entries.labels())
    }

    @Test
    fun theItemsOfTheMenuDoWhatTheySay() {
        val entries = terminalMenu(emptyList(), menuActions)

        listOf("Rename terminal", "Split right", "Split down", "Copy working directory", "Close terminal").forEach { entries.action(it).onClick() }

        assertEquals(listOf("rename", "splitRight", "splitDown", "copyDirectory", "close"), calls)
    }

    @Test
    fun theContextMenuIsTheDesignsInItsOrder() {
        val entries = terminalContextMenu(hasSelection = true, actions = contextActions)

        assertEquals(
            listOf("Copy", "Paste", "Select all", "---", "Clear scrollback", "---", "Split right", "Split down", "---", "Close terminal"),
            entries.labels(),
        )
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
            mapOf("Copy" to "⌘C", "Paste" to "⌘V", "Select all" to "⌘A", "Clear scrollback" to "⌘L", "Split right" to "⌘D", "Split down" to "⌘⇧D", "Close terminal" to "⌘W"),
            entries.filterIsInstance<MenuAction>().associate { it.label to it.shortcut },
        )
    }
}
