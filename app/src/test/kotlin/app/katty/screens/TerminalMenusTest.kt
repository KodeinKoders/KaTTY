package app.katty.screens

import app.katty.core.WorkspaceId
import app.katty.terminal.TerminalCommandOutcome
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
        exportHistory = { calls += "exportHistory" },
        restart = { calls += "restart" },
        close = { calls += "close" },
    )

    private val contextActions = ContextMenuActions(
        copy = { calls += "copy" },
        paste = { calls += "paste" },
        selectAll = { calls += "selectAll" },
        find = { calls += "find" },
        clearScreen = { calls += "clear" },
    )

    private fun contextMenu(hasSelection: Boolean = true, paneMenu: List<MenuEntry> = terminalMenu(emptyList(), "Default", menuActions)) =
        terminalContextMenu(hasSelection, contextActions, paneMenu)

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
            listOf("Rename terminal", "---", "Split right", "Split down", "---", "Move to workspace ›", "Color scheme", "---", "Copy working directory", "Export history…", "---", "Restart terminal", "Close terminal"),
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
        assertEquals(listOf("Rename terminal", "---", "Split right", "Split down", "---", "Color scheme", "---", "Copy working directory", "Export history…", "---", "Restart terminal", "Close terminal"), entries.labels())
    }

    @Test
    fun theItemsOfTheMenuDoWhatTheySay() {
        val entries = terminalMenu(emptyList(), "Default", menuActions)

        listOf("Rename terminal", "Split right", "Split down", "Copy working directory", "Export history…", "Restart terminal", "Close terminal").forEach { entries.action(it).onClick() }

        assertEquals(listOf("rename", "splitRight", "splitDown", "copyDirectory", "exportHistory", "restart", "close"), calls)
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
        val entries = contextMenu(paneMenu = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w"), "release-notes")), "Default", menuActions))

        assertEquals(
            listOf(
                "Copy", "Paste", "Select all", "---", "Find…", "Clear screen and scrollback", "---",
                "Rename terminal", "---", "Split right", "Split down", "---", "Move to workspace ›", "Color scheme", "---", "Copy working directory", "Export history…", "---", "Restart terminal", "Close terminal",
            ),
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
        assertEquals("⌘⇧D", shortcuts(contextMenu(paneMenu = terminalMenu(emptyList(), "Default", menuActions, vertical = true)))["Split right"])
        assertEquals("⌘D", shortcuts(contextMenu(paneMenu = terminalMenu(emptyList(), "Default", menuActions, vertical = true)))["Split down"])
        assertEquals("⌘F2", shortcuts(terminalMenu(emptyList(), "Default", menuActions))["Rename terminal"])
    }

    @Test
    fun restartingFromTheContextMenuRestartsTheTerminal() {
        contextMenu().action("Restart terminal").onClick()

        assertEquals(listOf("restart"), calls)
    }

    @Test
    fun copyIsOnlyForWhatIsSelected() {
        assertTrue(contextMenu(hasSelection = true).action("Copy").enabled)
        assertFalse(contextMenu(hasSelection = false).action("Copy").enabled)
    }

    @Test
    fun theShortcutsAreTheOnesTheWindowHandles() {
        val entries = contextMenu()

        assertEquals(
            mapOf(
                "Copy" to "⌘C", "Paste" to "⌘V", "Select all" to "⌘A", "Find…" to "⌘F", "Clear screen and scrollback" to "⌘L", "Rename terminal" to "⌘F2",
                "Split right" to "⌘D", "Split down" to "⌘⇧D", "Restart terminal" to "⌘⇧L", "Close terminal" to "⌘W",
            ),
            entries.filterIsInstance<MenuAction>().filter { it.shortcut != null }.associate { it.label to it.shortcut },
        )
    }

    @Test
    fun aRightClickOffersEveryItemOfTheMenuOfThePane() {
        val withName = TerminalMenuActions(
            rename = {}, useProcessName = { calls += "revert" }, splitRight = {}, splitDown = {}, moveTo = { calls += "move ${it.value}" },
            colorScheme = { calls += "colorScheme" }, copyDirectory = { calls += "copyDirectory" }, restart = {}, close = {},
        )
        val paneMenu = terminalMenu(listOf(WorkspaceChoice(WorkspaceId("w1"), "a")), "Nord", withName)

        val entries = contextMenu(paneMenu = paneMenu)

        // The items of the pane's menu, in its order, after the ones of the text: the same items, which do the same.
        assertEquals(paneMenu, entries.takeLast(paneMenu.size))
        assertEquals("---", entries.labels()[entries.size - paneMenu.size - 1])
        entries.action("Use process name").onClick()
        ((entries.filterIsInstance<MenuSubmenu>().single().entries.single()) as MenuAction).onClick()
        entries.action("Color scheme").onClick()
        entries.action("Copy working directory").onClick()
        assertEquals(listOf("revert", "move w1", "colorScheme", "copyDirectory"), calls)
        assertEquals("Nord", entries.action("Color scheme").hint)
    }

    @Test
    fun theItemsOfTheTextDoWhatTheySay() {
        val entries = contextMenu()

        listOf("Copy", "Paste", "Select all", "Find…", "Clear screen and scrollback").forEach { entries.action(it).onClick() }

        assertEquals(listOf("copy", "paste", "selectAll", "find", "clear"), calls)
    }

    // The bar of any command selects its output; once it has ended, and while the AI is on, it also asks why it failed (red) or what its result says.
    @Test
    fun theMenuOfABarSelectsItsOutputAndAsksWhyOrWhat() {
        val calls = mutableListOf<String>()
        fun entries(outcome: TerminalCommandOutcome, ai: Boolean = true) =
            commandMarkMenu(outcome, selectText = { calls += "select" }, ask = if (ai) ({ calls += "ask" }) else null).filterIsInstance<app.katty.ui.MenuAction>()
        fun labels(outcome: TerminalCommandOutcome, ai: Boolean = true) = entries(outcome, ai).map { it.label }

        assertEquals(listOf("Select text", "Ask AI why it failed"), labels(TerminalCommandOutcome.FAILED))
        assertEquals(listOf("Select text", "Explain result with AI"), labels(TerminalCommandOutcome.SUCCEEDED))
        assertEquals(listOf("Select text", "Explain result with AI"), labels(TerminalCommandOutcome.STOPPED))
        assertEquals(listOf("Select text"), labels(TerminalCommandOutcome.RUNNING), "A command that runs has no result yet")
        assertEquals(listOf("Select text"), labels(TerminalCommandOutcome.FAILED, ai = false), "The AI is off")

        entries(TerminalCommandOutcome.FAILED).forEach { it.onClick() }
        assertEquals(listOf("select", "ask"), calls)
    }

    // The right click of a terminal asks the AI about its last command, the question that its outcome makes.
    @Test
    fun theMenuOfATerminalAsksAboutItsLastCommand() {
        fun labels(question: app.katty.core.CommandQuestion?) = terminalContextMenu(
            hasSelection = false,
            actions = ContextMenuActions({}, {}, {}, {}, {}, lastCommandQuestion = question),
            paneMenu = emptyList(),
        ).filterIsInstance<app.katty.ui.MenuAction>().map { it.label }

        assertTrue("Ask AI why it failed" in labels(app.katty.core.CommandQuestion.WHY_FAILED))
        assertTrue("Explain result with AI" in labels(app.katty.core.CommandQuestion.EXPLAIN))
        assertTrue(labels(null).none { "AI" in it })
    }

    // The AI is asked for a command from the right click of a terminal while it is on, and the item says its shortcut.
    @Test
    fun theMenuOfATerminalAsksTheAiForACommandWhileItIsOn() {
        var asked = 0
        fun entries(ai: Boolean) = terminalContextMenu(
            hasSelection = false,
            actions = ContextMenuActions({}, {}, {}, {}, {}, askForCommand = if (ai) ({ asked++ }) else null),
            paneMenu = emptyList(),
        ).filterIsInstance<app.katty.ui.MenuAction>()

        val item = entries(ai = true).single { it.label == "Ask AI for a command…" }
        assertEquals("⌘I", item.shortcut)
        item.onClick()
        assertEquals(1, asked)
        assertTrue(entries(ai = false).none { "AI" in it.label })
    }

    private fun runActions(explain: (() -> Unit)? = null) = RunMenuActions(
        copy = { calls += "copy" },
        paste = { calls += "paste" },
        selectAll = { calls += "selectAll" },
        find = { calls += "find" },
        clearScreen = { calls += "clear" },
        explain = explain,
        copyDirectory = { calls += "copyDirectory" },
        restart = { calls += "restart" },
        minimize = { calls += "minimize" },
        stop = { calls += "stop" },
    )

    // The run terminal's right click has the terminal's text items, then what its header does: no split, rename or move, since it is no pane.
    @Test
    fun theMenuOfTheRunTerminalIsItsTextThenWhatItsHeaderDoes() {
        val entries = runContextMenu(hasSelection = true, ended = false, failed = false, runActions())

        assertEquals(
            listOf(
                "Copy", "Paste", "Select all", "---", "Find…", "Clear screen and scrollback", "---", "Copy working directory", "---",
                "Run the command again", "Minimize the run terminal", "Stop the run",
            ),
            entries.labels(),
        )
        for (label in listOf("Copy", "Paste", "Select all", "Find…", "Clear screen and scrollback", "Copy working directory", "Run the command again", "Minimize the run terminal", "Stop the run")) {
            entries.action(label).onClick()
        }
        assertEquals(listOf("copy", "paste", "selectAll", "find", "clear", "copyDirectory", "restart", "minimize", "stop"), calls)
    }

    // Its items say the shortcuts that act on it while it is shown, and stopping it is the red one.
    @Test
    fun theMenuOfTheRunTerminalSaysItsShortcuts() {
        val entries = runContextMenu(hasSelection = true, ended = false, failed = false, runActions())

        assertEquals(app.katty.AppShortcut.RESTART_TERMINAL.label, entries.action("Run the command again").shortcut)
        assertEquals(app.katty.AppShortcut.RUN_TOGGLE.label, entries.action("Minimize the run terminal").shortcut)
        assertEquals(app.katty.AppShortcut.CLOSE_TERMINAL.label, entries.action("Stop the run").shortcut)
        assertEquals(listOf("Stop the run"), entries.filterIsInstance<MenuAction>().filter { it.danger }.map { it.label })
    }

    // A command that has ended is not stopped: the run terminal is closed.
    @Test
    fun theRunTerminalWhoseCommandEndedIsClosedNotStopped() {
        val labels = runContextMenu(hasSelection = true, ended = true, failed = false, runActions()).labels()

        assertTrue("Close the run terminal" in labels)
        assertFalse("Stop the run" in labels)
    }

    // Copy is for what is selected: it is there, disabled, when nothing is.
    @Test
    fun theRunTerminalCopiesOnlyASelection() {
        assertFalse(runContextMenu(hasSelection = false, ended = false, failed = false, runActions()).action("Copy").enabled)
        assertTrue(runContextMenu(hasSelection = true, ended = false, failed = false, runActions()).action("Copy").enabled)
    }

    // The AI is asked why the command failed, only once it has failed and while the AI is on.
    @Test
    fun theRunTerminalAsksTheAiWhyItsCommandFailed() {
        var asked = 0
        val failed = runContextMenu(hasSelection = false, ended = true, failed = true, runActions(explain = { asked++ }))
        failed.action("Ask AI why it failed").onClick()
        assertEquals(1, asked)

        assertTrue(runContextMenu(hasSelection = false, ended = true, failed = false, runActions(explain = {})).labels().none { "AI" in it })
        assertTrue(runContextMenu(hasSelection = false, ended = true, failed = true, runActions(explain = null)).labels().none { "AI" in it })
    }
}
