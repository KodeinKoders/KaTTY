package app.katty

import app.katty.core.TerminalId
import app.katty.core.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenameGuardTest {
    private val terminal = TerminalId("t")
    private val workspace = WorkspaceId("w")

    @Test
    fun whatIsBeingRenamedIsNotClosedUnderTheField() {
        assertTrue(blockedWhileRenaming(AppShortcut.CLOSE_TERMINAL, terminal, workspace, renamingTerminal = terminal, renamingWorkspace = null))
        assertTrue(blockedWhileRenaming(AppShortcut.RESTART_TERMINAL, terminal, workspace, renamingTerminal = terminal, renamingWorkspace = null))
        assertTrue(blockedWhileRenaming(AppShortcut.CLOSE_WORKSPACE, terminal, workspace, renamingTerminal = null, renamingWorkspace = workspace))
    }

    @Test
    fun theOtherShortcutsAndTheOtherItemsAreNotKept() {
        assertFalse(blockedWhileRenaming(AppShortcut.CLOSE_TERMINAL, terminal, workspace, renamingTerminal = null, renamingWorkspace = workspace))
        assertFalse(blockedWhileRenaming(AppShortcut.CLOSE_TERMINAL, terminal, workspace, renamingTerminal = TerminalId("other"), renamingWorkspace = null))
        assertFalse(blockedWhileRenaming(AppShortcut.SPLIT_MAIN, terminal, workspace, renamingTerminal = terminal, renamingWorkspace = workspace))
    }
}
