package app.katty

import app.katty.core.Direction
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.SecureInput
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.terminals
import app.katty.screens.paletteCommands
import app.katty.screens.toTerminal
import app.katty.terminal.compose.TerminalSecureInput
import app.katty.terminals.TerminalHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowControllerTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val restarted = mutableListOf<TerminalId>()
    private val opened = mutableListOf<Pair<String, String>>()
    private val platform = object : WindowPlatform {
        override fun chooseFolder(title: String, initial: String?): String? = null
        override fun copy(text: String) = Unit
        override fun readClipboard(): String? = null
        override fun reveal(directory: String) = Unit
        override fun openIn(bundleId: String, directory: String) {
            opened += bundleId to directory
        }
        override fun openBoardWindow() = Unit
        override fun openAbout() = Unit
    }
    private val controller = WindowController(store, CoroutineScope(Dispatchers.Unconfined), platform, { restarted += it }, {})

    private val state get() = store.state.value
    private val terminal get() = state.selection.terminal!!

    init {
        store.addProject("/tmp/project")
        // Two panes, so that the focus has somewhere to go.
        store.split(state.selection.terminal!!, SplitDirection.ROW)
    }

    private fun showBoard() = store.updateSettings { it.copy(view = MainView.BOARD) }

    // A terminal whose shell runs a program: closing or restarting it asks first.
    private fun runProgram(id: TerminalId) {
        val handle = TerminalHandle(id, session = null)
        handle.processState.value = "vim"
        controller.handles = mapOf(id to handle)
    }

    @Test
    fun theRecentListOpensFromThePaletteAndItsChoiceAddsAndSelectsTheProject() {
        controller.paletteOpen = true
        controller.paletteHandlers().openRecent()

        assertTrue(controller.recentOpen)
        assertFalse(controller.paletteOpen, "The palette stays over the list")
        // Nothing the shortcuts do reaches the window behind it.
        val before = state
        assertTrue(controller.handle(AppShortcut.SPLIT_MAIN, null))
        assertEquals(before, state)

        controller.openRecentProject("/tmp/other", "Other")

        controller.handle(AppShortcut.OPEN_RECENT, null)
        assertTrue(controller.recentOpen, "The shortcut opens the list")
        controller.recentOpen = false

        assertFalse(controller.recentOpen)
        assertEquals("Other", state.selectedProject?.name)
        assertEquals("/tmp/other", state.selectedProject?.directory)
    }

    @Test
    fun thePaletteDoesNotActOnATerminalWhileTheBoardIsShown() {
        showBoard()
        val handlers = controller.paletteHandlers()
        val terminals = state.selectedWorkspace!!.layout.terminals().size
        val focused = terminal

        handlers.maximize()
        handlers.find()
        handlers.splitRight()
        handlers.closeTerminal()
        handlers.focusPane(Direction.LEFT)

        assertNull(state.selection.maximized, "Maximized a terminal that is not shown")
        assertNull(controller.findingTerminal, "Opened a find bar that is not shown")
        assertEquals(terminals, state.selectedWorkspace!!.layout.terminals().size, "Split or closed a terminal that is not shown")
        assertEquals(focused, terminal, "Moved the focus between panes that are not shown")
    }

    @Test
    fun thePaletteActsOnTheSelectedTerminalOverTheTerminals() {
        val handlers = controller.paletteHandlers()

        handlers.maximize()
        assertEquals(terminal, state.selection.maximized)
        handlers.find()
        assertEquals(terminal, controller.findingTerminal)
    }

    @Test
    fun aDialogSwallowsTheShortcutsAndThePaletteLetsOnlyItsOwnThrough() {
        controller.closingTerminal = terminal
        assertTrue(controller.handle(AppShortcut.MAXIMIZE, null), "A shortcut under a dialog is consumed")
        assertNull(state.selection.maximized, "and does nothing")
        controller.closingTerminal = null

        controller.paletteOpen = true
        assertTrue(controller.handle(AppShortcut.MAXIMIZE, null))
        assertNull(state.selection.maximized)
        assertTrue(controller.handle(AppShortcut.PALETTE, null))
        assertFalse(controller.paletteOpen, "The palette's shortcut closes it")

        controller.editingShortcuts = true
        assertFalse(controller.handle(AppShortcut.PALETTE, null), "The shortcuts dialog records every key")
    }

    @Test
    fun theShortcutsDoNotActOnATerminalWhileTheBoardIsShown() {
        showBoard()

        assertTrue(controller.handle(AppShortcut.MAXIMIZE, null))
        assertTrue(controller.handle(AppShortcut.FIND, null))

        assertNull(state.selection.maximized)
        assertNull(controller.findingTerminal)
    }

    @Test
    fun closingOrRestartingATerminalWhereAProgramRunsAsks() {
        runProgram(terminal)

        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        controller.handle(AppShortcut.RESTART_TERMINAL, null)

        assertEquals(terminal, controller.closingTerminal)
        assertEquals(emptyList(), restarted)
        controller.closingTerminal = null
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size, "Closed without asking")
    }

    @Test
    fun aShellAtItsPromptClosesAndRestartsAtOnce() {
        val id = terminal
        controller.handle(AppShortcut.RESTART_TERMINAL, null)
        assertEquals(listOf(id), restarted)

        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        assertNull(controller.closingTerminal)
        assertEquals(1, state.selectedWorkspace!!.layout.terminals().size)
    }

    @Test
    fun aTerminalThatIsBeingRenamedIsNotClosedUnderTheField() {
        controller.renamingTerminal = terminal

        assertTrue(controller.handle(AppShortcut.CLOSE_TERMINAL, null))

        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size)
    }

    @Test
    fun theOverlayIsOpenWhileAnythingIsOverTheTerminals() {
        assertFalse(controller.overlayOpen)
        controller.menuOpen = true
        assertTrue(controller.overlayOpen)
        controller.menuOpen = false
        controller.renamingWorkspace = state.selection.workspace
        assertTrue(controller.overlayOpen)
        controller.renamingWorkspace = null
        assertFalse(controller.overlayOpen)
    }

    @Test
    fun aRenameThatNothingShowsEnds() {
        controller.renamingWorkspace = state.selection.workspace
        controller.renamingTerminal = terminal
        showBoard()

        controller.dropStaleRenames()

        assertNull(controller.renamingWorkspace)
        assertNull(controller.renamingTerminal)
    }

    @Test
    fun thePaletteSetsWhenTheKeysAreKeptFromTheOtherApplications() {
        val commands = paletteCommands(state, emptyMap(), titleOf = { "zsh" }, handlers = controller.paletteHandlers())
        val entry = commands.entries.single { it.label == "Secure keyboard entry: always" }

        commands.run(entry)

        assertEquals(SecureInput.ALWAYS, state.settings.secureInput)
        // What the terminals are given of it.
        assertEquals(
            listOf(TerminalSecureInput.NEVER, TerminalSecureInput.PASSWORD_PROMPTS, TerminalSecureInput.ALWAYS),
            SecureInput.entries.map { it.toTerminal() },
        )
    }

    @Test
    fun quittingWithNothingToStopDoesNotAsk() = kotlinx.coroutines.runBlocking {
        assertTrue(controller.confirmQuit())
        assertNull(controller.quitting)
    }

    @Test
    fun quittingWhileAProgramRunsWaitsForTheAnswerAndTwoRequestsShareIt() {
        runProgram(terminal)
        val first = CoroutineScope(Dispatchers.Unconfined).async { controller.confirmQuit() }
        val second = CoroutineScope(Dispatchers.Unconfined).async { controller.confirmQuit() }

        val request = controller.quitting!!
        assertTrue(controller.blockingDialog, "The question is a dialog: the shortcuts wait")
        assertEquals(listOf(terminal), controller.stoppedByQuitting().map { it.terminal.id })
        assertFalse(first.isCompleted)
        request.answer.complete(false)

        assertFalse(kotlinx.coroutines.runBlocking { first.await() })
        assertFalse(kotlinx.coroutines.runBlocking { second.await() })
        assertNull(controller.quitting)
    }

    // The real git, so that the worktree is what git makes.
    private fun git(directory: java.nio.file.Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }

    @Test
    fun theEditorsOpenTheWorktreeThatTheSelectedTerminalIsInElseTheProject() {
        val root = java.nio.file.Files.createTempDirectory("katty-worktree").toRealPath()
        try {
            val repo = java.nio.file.Files.createDirectories(root.resolve("api"))
            git(repo, "init")
            java.nio.file.Files.writeString(repo.resolve("a.txt"), "a")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "first")
            val tree = root.resolve("api-feature")
            git(repo, "worktree", "add", "-b", "feature", tree.toString())
            val deep = java.nio.file.Files.createDirectories(tree.resolve("src/deep"))
            val counted = KattyStore(newId = { "w${counter++}" })
            counted.addProject(repo.toString())
            val terminalHere = counted.state.value.selection.terminal!!
            val inProject = WindowController(counted, CoroutineScope(Dispatchers.Unconfined), platform, {}, {})
            inProject.installedIdes = mapOf(app.katty.core.Ide.INTELLIJ_IDEA to "com.jetbrains.intellij")

            // In a folder of the worktree: the worktree's root.
            counted.setDirectory(terminalHere, deep.toString())
            val handlers = inProject.paletteHandlers()
            assertEquals(tree.toString(), handlers.worktree)
            handlers.openProjectIn(app.katty.core.Ide.INTELLIJ_IDEA)

            // Back in the project: the project.
            counted.setDirectory(terminalHere, repo.toString())
            val back = inProject.paletteHandlers()
            assertNull(back.worktree)
            back.openProjectIn(app.katty.core.Ide.INTELLIJ_IDEA)

            assertEquals(listOf("com.jetbrains.intellij" to tree.toString(), "com.jetbrains.intellij" to repo.toString()), opened)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun openingATerminalShowsItAndGivesItTheKeyboard() {
        val other = state.selectedWorkspace!!.layout.terminals().first { it.id != terminal }.id
        showBoard()
        controller.attentionOpen = true
        val tick = controller.focusTick

        controller.openTerminal(other)

        assertEquals(MainView.TERMINALS, state.settings.view)
        assertEquals(other, terminal)
        assertFalse(controller.attentionOpen)
        assertEquals(tick + 1, controller.focusTick)
    }
}
