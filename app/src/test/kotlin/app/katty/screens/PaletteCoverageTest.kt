package app.katty.screens

import app.katty.AppShortcut
import app.katty.TerminalKeys
import app.katty.core.KattyStore
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.SplitDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the keyboard does, the palette does: every shortcut of the window, and every key of the terminal, is a command of it. */
class PaletteCoverageTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value
    private val calls = mutableListOf<String>()

    // Two projects, the first with two workspaces, the first of which has two terminals: the one that is selected.
    private fun built() {
        val api = store.addProject("/dev/api")!!
        val first = state.selection.workspace!!
        store.split(state.selection.terminal!!, SplitDirection.ROW)
        store.addWorkspace(api, "second")
        store.addProject("/dev/web")
        store.selectWorkspace(first)
    }

    private val handlers = PaletteHandlers(
        addProject = {}, newWorkspace = {}, renameWorkspace = {}, closeWorkspace = {}, splitRight = {}, splitDown = {}, renameTerminal = {},
        closeTerminal = {}, restartTerminal = {}, find = {}, clearScreen = {}, maximize = {}, colorScheme = {}, toggleSidebar = {}, showView = {},
        setLayout = {}, setTheme = {}, jumpToWaiting = {}, markAllSeen = {}, openBoardWindow = {}, openTerminal = {}, openWorkspace = {},
        focusPane = { calls += "focus $it" }, resizeSplit = { calls += "resize $it" }, findNext = { calls += "findNext" }, findPrevious = { calls += "findPrevious" },
        copy = { calls += "copy" }, paste = { calls += "paste" }, selectAll = { calls += "selectAll" },
        previousPrompt = { calls += "previousPrompt" }, nextPrompt = { calls += "nextPrompt" },
        scrollPageUp = { calls += "pageUp" }, scrollPageDown = { calls += "pageDown" }, scrollToTop = { calls += "top" }, scrollToBottom = { calls += "bottom" },
    )

    private fun commands(vertical: Boolean = false, hasSelection: Boolean = true) =
        paletteCommands(state, emptyMap(), titleOf = { it.name ?: "zsh" }, handlers = handlers, vertical = vertical, hasSelection = hasSelection)

    private fun PaletteCommands.shortcuts(): Set<String?> = entries.filter { it.group == PaletteGroup.COMMANDS }.map { it.shortcut }.toSet()

    // What can have no command of its own, and why: a shortcut that is not in this list must be a command of the palette.
    private val covered = mapOf(
        AppShortcut.PALETTE to "it is the palette itself",
        AppShortcut.WORKSPACE_UP to "the previous workspace command does the same, along the column of the sidebar",
        AppShortcut.WORKSPACE_DOWN to "the next workspace command does the same, along the column of the sidebar",
        AppShortcut.WORKSPACE_LEFT to "the previous workspace command does the same, along the row of the tabs",
        AppShortcut.WORKSPACE_RIGHT to "the next workspace command does the same, along the row of the tabs",
        AppShortcut.WORKSPACE_NUMBER to "every workspace is an entry of the palette",
        AppShortcut.PANE_NUMBER to "every terminal is an entry of the palette",
    )

    @Test
    fun everyShortcutOfTheWindowIsACommandOfThePalette() {
        built()
        for (vertical in listOf(false, true)) {
            val shortcuts = commands(vertical).shortcuts()
            for (shortcut in AppShortcut.entries) {
                if (shortcut in covered) continue
                assertTrue(shortcut.label in shortcuts, "$shortcut (${shortcut.label}) is not a command of the palette${if (vertical) " in the vertical layout" else ""}")
            }
        }
    }

    @Test
    fun theCommandsThatStandInForAShortcutAreThere() {
        built()
        val labels = commands().entries.map { it.label }

        assertTrue("Previous workspace" in labels && "Next workspace" in labels)
        // The numbers of the workspaces and of the panes: every workspace and every terminal is an entry.
        assertEquals(state.allTerminals().size, commands().entries.count { it.group == PaletteGroup.TERMINALS })
        assertEquals(state.projects.sumOf { it.workspaces.size }, commands().entries.count { it.group == PaletteGroup.WORKSPACES })
    }

    @Test
    fun everyKeyOfTheTerminalIsACommandOfThePalette() {
        built()
        val shortcuts = commands().shortcuts()

        for (key in listOf(
            TerminalKeys.COPY, TerminalKeys.PASTE, TerminalKeys.SELECT_ALL, TerminalKeys.PREVIOUS_PROMPT, TerminalKeys.NEXT_PROMPT,
            TerminalKeys.SCROLL_PAGE_UP, TerminalKeys.SCROLL_PAGE_DOWN, TerminalKeys.SCROLL_TO_TOP, TerminalKeys.SCROLL_TO_BOTTOM,
        )) assertTrue(key in shortcuts, "$key is not a command of the palette")
    }

    private fun entry(label: String, vertical: Boolean = false, hasSelection: Boolean = true): PaletteEntry =
        commands(vertical, hasSelection).entries.single { it.group == PaletteGroup.COMMANDS && it.label == label }

    private fun run(label: String) {
        val commands = commands()
        commands.run(commands.entries.single { it.label == label })
    }

    @Test
    fun theCommandsMoveTheFocusAndTheDividersInTheDirectionTheySay() {
        built()

        run("Focus the pane on the left")
        run("Focus the pane on the right")
        run("Focus the pane above")
        run("Focus the pane below")
        run("Move the divider to the left")
        run("Move the divider to the right")
        run("Move the divider up")
        run("Move the divider down")

        assertEquals(
            listOf("focus LEFT", "focus RIGHT", "focus UP", "focus DOWN", "resize LEFT", "resize RIGHT", "resize UP", "resize DOWN"),
            calls,
        )
    }

    @Test
    fun theFindCommandsStepThroughTheMatches() {
        built()

        run("Find next match")
        run("Find previous match")

        assertEquals(listOf("findNext", "findPrevious"), calls)
    }

    @Test
    fun theTerminalCommandsDoWhatTheKeysDo() {
        built()

        for (label in listOf(
            "Copy", "Paste", "Select all", "Jump to the previous prompt", "Jump to the next prompt", "Scroll up a page", "Scroll down a page",
            "Scroll to the top", "Scroll to the bottom",
        )) run(label)

        assertEquals(listOf("copy", "paste", "selectAll", "previousPrompt", "nextPrompt", "pageUp", "pageDown", "top", "bottom"), calls)
    }

    @Test
    fun withASingleTerminalThereIsNoPaneToGoToOrDividerToMove() {
        store.addProject("/dev/api")

        val labels = commands().entries.map { it.label }

        assertFalse(labels.any { it.startsWith("Focus the pane") || it.startsWith("Move the divider") })
        assertTrue("Paste" in labels && "Find next match" in labels, "What does not need a split is there")
    }

    @Test
    fun copyIsOfferedOnlyWhenSomethingIsSelected() {
        built()

        assertTrue(commands(hasSelection = true).entries.any { it.label == "Copy" })
        assertFalse(commands(hasSelection = false).entries.any { it.label == "Copy" })
        assertEquals(TerminalKeys.COPY, entry("Copy").shortcut)
    }
}
