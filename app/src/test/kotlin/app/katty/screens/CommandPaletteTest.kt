@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandPaletteTest {
    private val entries = listOf(
        PaletteEntry("w1", PaletteGroup.WORKSPACES, "refactor-auth", "api-gateway"),
        PaletteEntry("w2", PaletteGroup.WORKSPACES, "release-notes", "api-gateway"),
        PaletteEntry("t1", PaletteGroup.TERMINALS, "Claude Code", "refactor-auth  ·  working"),
        PaletteEntry("t2", PaletteGroup.TERMINALS, "Codex", "refactor-auth  ·  needs input"),
        PaletteEntry("c1", PaletteGroup.COMMANDS, "Restart terminal", shortcut = "⌘R"),
        PaletteEntry("c2", PaletteGroup.COMMANDS, "Rename workspace", shortcut = "F2"),
    )

    @Test
    fun listsTheGroupsWithTheirEntriesAndFooter() = runComposeUiTest {
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = {}, onDismiss = {}) } }

        onNodeWithContentDescription("Command palette").assertExists()
        onNodeWithText("WORKSPACES").assertExists()
        onNodeWithText("TERMINALS").assertExists()
        onNodeWithText("COMMANDS").assertExists()
        onNodeWithText("Navigate").assertExists()
        onNodeWithText("Open").assertExists()
        onNodeWithText("Toggle palette").assertExists()
        assertEquals(2, onAllNodesWithText("api-gateway").fetchSemanticsNodes().size)
    }

    @Test
    fun typingNarrowsTheListToWhatMatches() = runComposeUiTest {
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = {}, onDismiss = {}) } }

        onNode(hasSetTextAction()).performTextInput("restart")
        waitForIdle()

        onNodeWithContentDescription("Restart terminal").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Codex refactor-auth  ·  needs input").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("WORKSPACES").fetchSemanticsNodes().size)
    }

    @Test
    fun saysWhenNothingMatches() = runComposeUiTest {
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = {}, onDismiss = {}) } }

        onNode(hasSetTextAction()).performTextInput("qqq")
        waitForIdle()

        onNodeWithText("No results.").assertExists()
    }

    @Test
    fun enterDoesWhatIsSelectedAndTheArrowsMoveTheSelection() = runComposeUiTest {
        val ran = mutableListOf<String>()
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = { ran += it.id }, onDismiss = {}) } }

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("w1", "t1", "w2"), ran)
    }

    @Test
    fun theArrowsGoRoundTheList() = runComposeUiTest {
        val ran = mutableListOf<String>()
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = { ran += it.id }, onDismiss = {}) } }

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("c2"), ran)
    }

    @Test
    fun aClickDoesWhatTheRowSays() = runComposeUiTest {
        val ran = mutableListOf<String>()
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = { ran += it.id }, onDismiss = {}) } }

        onNodeWithContentDescription("Codex refactor-auth  ·  needs input").performClick()

        assertEquals(listOf("t2"), ran)
    }

    @Test
    fun escapeClosesIt() = runComposeUiTest {
        var closed = false
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = {}, onDismiss = { closed = true }) } }

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }

        assertTrue(closed)
    }

    @Test
    fun aNewQueryStartsFromTheTopAgain() = runComposeUiTest {
        val ran = mutableListOf<String>()
        setContent { KattyTheme(false) { CommandPalette(entries, onRun = { ran += it.id }, onDismiss = {}) } }

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        onNode(hasSetTextAction()).performTextInput("re")
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, ran.size)
        assertEquals(PaletteGroup.WORKSPACES, entries.first { it.id == ran.single() }.group)
    }

    @Test
    fun theShortcutsAreDrawnAsKeys() {
        assertEquals("⌘ ⇧ J", shortcutKeys("⌘⇧J"))
        assertEquals("⌘ K", shortcutKeys("⌘K"))
        assertEquals("F2", shortcutKeys("F2"))
    }

    @Test
    fun drawsThePaletteInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("palette-${if (dark) "dark" else "light"}", 1440, 900, dark = dark) { CommandPalette(entries, onRun = {}, onDismiss = {}) }
            // The first row is selected.
            assertTrue(rendered.count(if (dark) 0x2E436E else 0xD4E2FF) > 1000)
        }
    }

    @Test
    fun theButtonOfTheTitleBarSaysWhatItDoesInBothLayouts() = runComposeUiTest {
        setContent { KattyTheme(false) { androidx.compose.foundation.layout.Column { PaletteButton(compact = false, onClick = {}); PaletteButton(compact = true, onClick = {}) } } }

        onNodeWithText("Search or run a command…").assertExists()
        onNodeWithText("Commands").assertExists()
    }

    // --- The entries that the window gives it ---

    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value
    private val calls = mutableListOf<String>()

    private val handlers = PaletteHandlers(
        addProject = { calls += "addProject" }, newWorkspace = { calls += "newWorkspace" }, renameWorkspace = { calls += "renameWorkspace" },
        closeWorkspace = { calls += "closeWorkspace" }, splitRight = { calls += "splitRight" }, splitDown = { calls += "splitDown" },
        renameTerminal = { calls += "renameTerminal" }, closeTerminal = { calls += "closeTerminal" }, restartTerminal = { calls += "restart" },
        find = { calls += "find" }, clearScreen = { calls += "clear" }, maximize = { calls += "maximize" }, colorScheme = { calls += "colorScheme" },
        toggleSidebar = { calls += "sidebar" }, showView = { calls += "view $it" }, setLayout = { calls += "layout $it" }, setTheme = { calls += "theme $it" },
        jumpToWaiting = { calls += "jump" }, markAllSeen = { calls += "seen" }, openBoardWindow = { calls += "boardWindow" }, openTerminal = { calls += "terminal ${it.value}" },
        openWorkspace = { calls += "workspace ${it.value}" },
        editShortcuts = { calls += "editShortcuts" }, selectProject = { calls += "selectProject ${it.value}" }, moveTerminalTo = { calls += "move ${it.value}" },
    )

    private fun commands(infos: Map<TerminalId, AgentInfo> = emptyMap()) = paletteCommands(state, infos, titleOf = { it.name ?: "zsh" }, handlers = handlers)

    @Test
    fun withNothingOpenOnlyTheCommandsThatDoNotNeedOneAreThere() {
        val labels = commands().entries.map { it.label }

        assertTrue("Add project folder…" in labels)
        assertTrue("Jump to next waiting agent" in labels)
        assertFalse("New workspace" in labels)
        assertFalse("Split right" in labels)
        assertFalse("Close terminal" in labels)
    }

    @Test
    fun theBoardCanBeOpenedInAWindowFromThePaletteWhateverIsOpen() {
        val entry = commands().entries.single { it.label == "Open the board in a window" }
        store.addProject("/dev/api-gateway")
        // It is there with a project open too, and in the board view.
        assertTrue(commands().entries.any { it.label == "Open the board in a window" })

        commands().run(entry)

        assertEquals(listOf("boardWindow"), calls)
    }

    @Test
    fun listsTheWorkspacesTheTerminalsAndTheCommandsOfTheState() {
        store.addProject("/dev/api-gateway")
        store.renameWorkspace(state.selection.workspace!!, "refactor-auth")
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        val infos = mapOf(second to AgentInfo(AgentReport(AgentState.NEEDS_INPUT, "codex"), 0))

        val entries = commands(infos).entries

        val workspace = entries.single { it.group == PaletteGroup.WORKSPACES }
        assertEquals("refactor-auth", workspace.label)
        assertEquals("api-gateway", workspace.context)
        val terminals = entries.filter { it.group == PaletteGroup.TERMINALS }
        assertEquals(listOf("zsh  ·  idle".let { "refactor-auth  ·  idle" }, "refactor-auth  ·  needs input"), terminals.map { it.context })
        val shortcuts = entries.filter { it.group == PaletteGroup.COMMANDS }.associate { it.label to it.shortcut }
        assertEquals("⌘D", shortcuts["Split right"])
        assertEquals("⌘⇧D", shortcuts["Split down"])
        assertEquals("⌘R", shortcuts["Restart terminal"])
        assertEquals("⌘⇧F2", shortcuts["Rename workspace"])
        assertEquals("⌘F2", shortcuts["Rename terminal"])
        assertEquals("⌘L", shortcuts["Clear screen and scrollback"])
        assertEquals("⌘⇧W", shortcuts["Close workspace"])
    }

    @Test
    fun theShortcutWithoutShiftSplitsInTheMainDirectionOfTheLayout() {
        store.addProject("/dev/api-gateway")

        fun shortcuts(vertical: Boolean) = paletteCommands(state, emptyMap(), titleOf = { "zsh" }, handlers = handlers, vertical = vertical)
            .entries.filter { it.group == PaletteGroup.COMMANDS }.associate { it.label to it.shortcut }

        // Right in the horizontal layout, down in the vertical one: the same keys split the same way as the pane's buttons do.
        assertEquals("⌘D", shortcuts(vertical = false)["Split right"])
        assertEquals("⌘⇧D", shortcuts(vertical = false)["Split down"])
        assertEquals("⌘⇧D", shortcuts(vertical = true)["Split right"])
        assertEquals("⌘D", shortcuts(vertical = true)["Split down"])
    }

    @Test
    fun runningAnEntryDoesWhatItSays() {
        store.addProject("/dev/api-gateway")
        val terminal = state.selection.terminal!!
        val workspace = state.selection.workspace!!
        val commands = commands()

        for (entry in commands.entries) {
            when (entry.id) {
                "workspace:${workspace.value}", "terminal:${terminal.value}", "command:split-right", "command:theme-dark", "command:layout-vertical", "command:view" -> commands.run(entry)
            }
        }

        assertEquals(listOf("workspace ${workspace.value}", "terminal ${terminal.value}", "splitRight", "view BOARD", "layout VERTICAL", "theme DARK"), calls)
    }

    @Test
    fun theDialogOfTheShortcutsIsACommandThatHasNoShortcutOfItsOwn() {
        store.addProject("/dev/api-gateway")
        val entry = commands().entries.single { it.label == "Edit keyboard shortcuts…" }

        assertEquals(null, entry.shortcut)
        commands().run(entry)
        assertEquals(listOf("editShortcuts"), calls)
    }

    @Test
    fun theProjectStepsAreThereOnlyWhenThereIsAnotherProjectToGoTo() {
        store.addProject("/dev/api-gateway")
        assertFalse(commands().entries.any { it.label == "Next project" })

        store.addProject("/dev/web-client")
        val entries = commands().entries

        assertEquals("⌘⌥]", entries.single { it.label == "Next project" }.shortcut)
        assertEquals("⌘⌥[", entries.single { it.label == "Previous project" }.shortcut)
    }

    @Test
    fun theWorkspaceStepsAreThereOnlyWhenThereIsAnotherWorkspaceToGoTo() {
        store.addProject("/dev/api")
        assertFalse(commands().entries.any { it.label == "Next workspace" })

        store.addWorkspace(state.selection.project!!)
        val entries = commands().entries

        assertEquals("⌘⇧]", entries.single { it.label == "Next workspace" }.shortcut)
        assertEquals("⌘⇧[", entries.single { it.label == "Previous workspace" }.shortcut)
    }

    private fun labels() = commands().entries.map { it.label }

    @Test
    fun theProjectsAreListedAndChosenLikeTheWorkspaces() {
        store.addProject("/dev/api-gateway")
        store.addProject("/dev/web-client")
        val entries = commands().entries

        val projects = entries.filter { it.group == PaletteGroup.PROJECTS }

        assertEquals(listOf("api-gateway", "web-client"), projects.map { it.label })
        assertEquals(listOf("/dev/api-gateway", "/dev/web-client"), projects.map { it.context })
        commands().run(projects.first())
        assertEquals(listOf("project ${state.projects.first().id.value}"), calls.map { it.replace(Regex("^selectProject "), "project ") })
    }

    @Test
    fun theProjectCommandsAreThereForTheSelectedProjectAndMovesOnlyWhereThereIsRoom() {
        store.addProject("/dev/api-gateway")
        assertTrue("Rename project" in labels())
        assertTrue("Remove project…" in labels())
        assertTrue("Reveal project in the file manager" in labels())
        assertFalse("Move project up" in labels() || "Move project down" in labels(), "It is the only one")

        store.addProject("/dev/web-client")
        assertTrue("Move project up" in labels())
        assertFalse("Move project down" in labels(), "The selected one is the last")
    }

    @Test
    fun aWorkspaceMovesUpOrDownOnlyWhereThereIsRoom() {
        store.addProject("/dev/api-gateway")
        val project = state.selection.project!!
        assertFalse("Move workspace up" in labels() || "Move workspace down" in labels())

        store.addWorkspace(project)

        assertTrue("Move workspace up" in labels())
        assertFalse("Move workspace down" in labels())
    }

    @Test
    fun aTerminalCanBeMovedToTheOtherWorkspacesOfItsProjectFromThePalette() {
        store.addProject("/dev/api-gateway")
        val project = state.selection.project!!
        store.renameWorkspace(state.selection.workspace!!, "first")
        val second = store.addWorkspace(project, "second")!!
        store.selectWorkspace(state.projects.first().workspaces.first().id)
        val entry = commands().entries.single { it.label == "Move terminal to “second”" }

        commands().run(entry)

        assertEquals(listOf("move ${second.value}"), calls)
    }

    @Test
    fun theCommandsOfATerminalThatHasANameIncludeGivingItUp() {
        store.addProject("/dev/api-gateway")
        assertFalse("Use process name for the terminal" in labels())

        store.renameTerminal(state.selection.terminal!!, "build")

        assertTrue("Use process name for the terminal" in labels())
    }

    @Test
    fun theSettingsAndTheUsageHaveCommandsThatSayWhatTheyDoNext() {
        store.addProject("/dev/api-gateway")
        assertTrue("Turn font ligatures off" in labels())
        assertTrue("Show the usage in a compact form" in labels())

        store.updateSettings { it.copy(ligatures = false, compactUsage = true) }

        assertTrue("Turn font ligatures on" in labels())
        assertTrue("Show the usage in full" in labels())
        for (label in listOf("Choose the default color scheme…", "Refresh the usage of the agents", "Open the main menu", "Bigger terminal font", "Smaller terminal font", "Default terminal font size", "Bigger interface text", "Smaller interface text", "Default interface text size", "Show the agents that need you")) {
            assertTrue(label in labels(), label)
        }
    }

    @Test
    fun theViewCommandSaysWhereItGoes() {
        store.addProject("/a")
        assertTrue(commands().entries.any { it.label == "Show the agent board" })

        store.updateSettings { it.copy(view = MainView.BOARD) }

        assertTrue(commands().entries.any { it.label == "Show terminals" })
    }

    @Test
    fun theMaximizeCommandSaysWhatItDoes() {
        store.addProject("/a")
        assertTrue(commands().entries.any { it.label == "Maximize terminal" })

        store.maximize(state.selection.terminal)

        assertTrue(commands().entries.any { it.label == "Restore terminal" })
    }

    @Test
    fun anEntryThatIsNotThereDoesNothing() {
        commands().run(PaletteEntry("command:nothing", PaletteGroup.COMMANDS, "x"))

        assertEquals(emptyList(), calls)
    }

}
