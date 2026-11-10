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
import app.katty.terminal.TerminalSession
import app.katty.terminals.ExportFormat
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
    private val openedFiles = mutableListOf<Pair<app.katty.core.Ide, app.katty.terminal.TerminalFilePosition>>()
    // What the save panel is asked (its folder and the name it proposes), and what it answers.
    private val saveRequests = mutableListOf<Pair<String?, String>>()
    private var saveAnswer: String? = null
    private val sessions = mutableListOf<TerminalSession>()
    private val platform = object : WindowPlatform {
        override fun chooseFolder(title: String, initial: String?): String? = null
        override fun chooseSaveFile(title: String, directory: String?, name: String): String? {
            saveRequests += directory to name
            return saveAnswer
        }
        override fun copy(text: String) = Unit
        override fun readClipboard(): String? = null
        override fun reveal(directory: String) = Unit
        override fun openIn(bundleId: String, directory: String) {
            opened += bundleId to directory
        }
        override fun openFile(ide: app.katty.core.Ide, bundleId: String, file: app.katty.terminal.TerminalFilePosition) {
            openedFiles += ide to file
        }
        override fun openBoardWindow() = Unit
        override fun openAbout() = Unit
    }
    private val seen = mutableListOf<TerminalId>()
    private val controller = WindowController(store, CoroutineScope(Dispatchers.Unconfined), platform, { restarted += it }, {}, { seen += it })

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

    private val file = app.katty.terminal.TerminalFilePosition("/tmp/project/src/Foo.kt", 42, 5)
    private val idea = app.katty.core.Ide.INTELLIJ_IDEA
    private val xcode = app.katty.core.Ide.XCODE

    @Test
    fun theIdeOfAFileIsAskedForInAWorkspaceThatHasNone() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij", xcode to "com.apple.dt.Xcode")

        controller.openFile(terminal, file)

        assertEquals(IdeRequest(state.selection.workspace!!, file), controller.choosingIde)
        assertTrue(controller.blockingDialog, "The question has the keys")
        assertEquals(emptyList(), openedFiles, "Nothing is opened before the answer")
    }

    @Test
    fun anIdeThatTheUserSaysToRememberOpensTheNextFilesOfTheWorkspaceAtOnce() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij", xcode to "com.apple.dt.Xcode")
        controller.openFile(terminal, file)

        controller.answerIde(xcode, remember = true)

        assertNull(controller.choosingIde)
        assertEquals("XCODE", state.selectedWorkspace!!.ide)
        val next = file.copy(line = 7)
        controller.openFile(terminal, next)
        assertNull(controller.choosingIde, "Not asked again")
        assertEquals(listOf(xcode to file, xcode to next), openedFiles)
    }

    @Test
    fun anIdeThatIsNotRememberedIsAskedForAgain() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij")
        controller.openFile(terminal, file)

        controller.answerIde(idea, remember = false)
        controller.openFile(terminal, file)

        assertNull(state.selectedWorkspace!!.ide)
        assertEquals(listOf(idea to file), openedFiles)
        assertEquals(IdeRequest(state.selection.workspace!!, file), controller.choosingIde)
    }

    @Test
    fun aRememberedIdeThatIsNotInstalledAnyMoreIsAskedForAgain() {
        store.setWorkspaceIde(state.selection.workspace!!, xcode)
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij")

        controller.openFile(terminal, file)

        assertEquals(emptyList(), openedFiles)
        assertEquals(IdeRequest(state.selection.workspace!!, file), controller.choosingIde)
    }

    @Test
    fun theIdeOfAWorkspaceIsChosenAndTakenOffFromItsMenu() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij")
        val workspace = state.selection.workspace!!

        controller.editingWorkspaceIde = workspace
        assertTrue(controller.blockingDialog)
        controller.setWorkspaceIde(workspace, idea)
        assertNull(controller.editingWorkspaceIde)
        assertEquals("INTELLIJ_IDEA", state.selectedWorkspace!!.ide)
        controller.openFile(terminal, file)
        assertEquals(listOf(idea to file), openedFiles)

        controller.setWorkspaceIde(workspace, null)
        controller.openFile(terminal, file)
        assertEquals(IdeRequest(workspace, file), controller.choosingIde, "Asked at each file again")
    }

    @Test
    fun theQuestionOfAWorkspaceThatIsClosedGoes() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij")
        val project = state.selection.project!!
        val other = store.addWorkspace(project, "other")!!
        store.selectWorkspace(other)
        controller.openFile(terminal, file)
        controller.editingWorkspaceIde = other

        store.closeWorkspace(other)
        controller.dropStaleRenames()

        assertNull(controller.choosingIde)
        assertNull(controller.editingWorkspaceIde)
    }

    @Test
    fun theFinderOpensWhatIsClickedAndIsNotOfferedTheProject() {
        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij", app.katty.core.Ide.FINDER to "com.apple.finder")
        controller.openFile(terminal, file)
        controller.answerIde(app.katty.core.Ide.FINDER, remember = true)

        assertEquals(listOf(app.katty.core.Ide.FINDER to file), openedFiles)
        assertEquals("FINDER", state.selectedWorkspace!!.ide)
        assertEquals(listOf(idea), controller.paletteHandlers().ides, "A project's folder is already revealed by its own command")
    }

    @Test
    fun thePaletteChoosesTheIdeOfTheSelectedWorkspaceWhenOneIsInstalled() {
        val commands = { paletteCommands(state, emptyMap(), titleOf = { it.id.value }, handlers = controller.paletteHandlers()) }
        assertTrue(commands().entries.none { it.id == "command:workspace-ide" }, "No IDE to choose")

        controller.installedIdes = mapOf(idea to "com.jetbrains.intellij")
        val palette = commands()
        palette.run(palette.entries.single { it.id == "command:workspace-ide" })

        assertEquals(state.selection.workspace, controller.editingWorkspaceIde)
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

    @Test
    fun theShortcutReopensWhatWasClosedLastAndShowsItWithTheKeyboard() {
        val closed = terminal
        controller.closeTerminal(closed)
        showBoard()
        val tick = controller.focusTick

        assertTrue(controller.handle(AppShortcut.REOPEN_CLOSED, null))

        assertEquals(closed, terminal)
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size)
        assertEquals(MainView.TERMINALS, state.settings.view, "From the board too: it is shown")
        assertEquals(tick + 1, controller.focusTick)
    }

    @Test
    fun withNothingClosedTheShortcutDoesNothing() {
        showBoard()
        val before = state
        val tick = controller.focusTick

        assertTrue(controller.handle(AppShortcut.REOPEN_CLOSED, null))

        assertEquals(before, state)
        assertEquals(tick, controller.focusTick)
    }

    @Test
    fun aDialogKeepsTheShortcutFromReopening() {
        val closed = terminal
        controller.closeTerminal(closed)
        controller.closingWorkspace = state.selection.workspace

        controller.handle(AppShortcut.REOPEN_CLOSED, null)

        assertNull(state.locate(closed))
    }

    @Test
    fun thePaletteReopensWhatWasClosedAndSaysWhatItIs() {
        fun commands() = paletteCommands(state, emptyMap(), titleOf = { "zsh" }, handlers = controller.paletteHandlers()).let { c -> c to c.entries.filter { it.id == "command:reopen-closed" } }
        assertTrue(commands().second.isEmpty(), "Nothing was closed")

        val closed = terminal
        controller.closeTerminal(closed)
        val (withTerminal, terminalEntry) = commands()
        assertEquals("Reopen closed terminal", terminalEntry.single().label)
        assertEquals("⌘⇧T", terminalEntry.single().shortcut)
        withTerminal.run(terminalEntry.single())
        assertEquals(closed, terminal)

        val workspace = state.selectedWorkspace!!
        controller.closeWorkspace(workspace.id)
        assertEquals("Reopen closed workspace “${workspace.name}”", commands().second.single().label)
    }

    @Test
    fun theShortcutOpensAndClosesTheSearchOfEveryTerminalWhichKeepsTheOthersFromActing() {
        controller.handle(AppShortcut.SEARCH_ALL, null)
        assertTrue(controller.searchingAll)
        assertTrue(controller.overlayOpen)

        val before = state
        assertTrue(controller.handle(AppShortcut.SPLIT_MAIN, null), "Swallowed")
        assertEquals(before, state)

        controller.handle(AppShortcut.SEARCH_ALL, null)
        assertFalse(controller.searchingAll)
    }

    @Test
    fun thePaletteOpensTheSearchOfEveryTerminalInItsPlace() {
        controller.paletteOpen = true

        controller.paletteHandlers().searchAll()

        assertTrue(controller.searchingAll)
        assertFalse(controller.paletteOpen)
    }

    @Test
    fun goingToARowOfTheSearchShowsItsTerminalWithItsFindBarOnTheText() {
        val other = state.selectedWorkspace!!.layout.terminals().first { it.id != terminal }.id
        showBoard()
        controller.searchingAll = true
        val request = controller.findRequest

        controller.openSearchResult(other, row = 40, query = "needle")

        assertFalse(controller.searchingAll)
        assertEquals(MainView.TERMINALS, state.settings.view, "From the board too")
        assertEquals(other, terminal)
        assertEquals(other, controller.findingTerminal)
        assertEquals("needle", controller.findQuery)
        assertEquals(request + 1, controller.findRequest, "A bar that is open is made again with the text")
    }

    @Test
    fun aFindBarThatTheUserOpensStartsEmpty() {
        controller.openSearchResult(terminal, row = 0, query = "needle")
        controller.findingTerminal = null

        controller.handle(AppShortcut.FIND, null)

        assertEquals(terminal, controller.findingTerminal)
        assertEquals("", controller.findQuery)
    }

    @Test
    fun aRowOfATerminalThatIsGoneGoesNowhere() {
        controller.searchingAll = true

        controller.openSearchResult(TerminalId("gone"), row = 0, query = "needle")

        assertFalse(controller.searchingAll)
        assertNull(controller.findingTerminal)
    }

    @Test
    fun goingToATerminalIsHavingSeenWhatItFinished() {
        val other = state.selectedWorkspace!!.layout.terminals().first { it.id != terminal }.id

        controller.openTerminal(other)

        assertEquals(listOf(other), seen)
    }

    @Test
    fun theJumpGoesToATerminalThatFinishedAndSeesIt() {
        val other = state.selectedWorkspace!!.layout.terminals().first { it.id != terminal }.id
        controller.infos = mapOf(other to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.DONE, "claude"), 1))

        controller.handle(AppShortcut.JUMP_TO_WAITING, null)

        assertEquals(other, terminal)
        assertEquals(listOf(other), seen)
    }

    @kotlin.test.AfterTest
    fun closeSessions() {
        sessions.forEach { it.close() }
    }

    // The selected terminal, with a session whose program wrote [text].
    private fun terminalThatWrote(text: String): TerminalId {
        val session = TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "printf '$text\\r\\n'; sleep 30"), shellIntegration = false).also { sessions += it }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (text !in session.screenText()) {
            check(System.nanoTime() < deadline) { "The program did not write: ${session.screenText()}" }
            Thread.sleep(10)
        }
        controller.handles = mapOf(terminal to TerminalHandle(terminal, session))
        return terminal
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(10)
        }
    }

    private val now = java.time.LocalDateTime.of(2026, 11, 10, 19, 43)

    @Test
    fun theHistoryIsExportedInTheFormatThatIsChosenWhereTheUserSays() {
        val id = terminalThatWrote("hello export")
        val folder = java.nio.file.Files.createTempDirectory("katty-export")
        saveAnswer = folder.resolve("history.html").toString()

        controller.paletteHandlers().exportHistory()
        assertEquals(id, controller.exportingHistory, "The format is asked first")
        assertTrue(controller.blockingDialog)
        controller.answerExport(ExportFormat.HTML, now)

        assertNull(controller.exportingHistory)
        val (directory, name) = saveRequests.single()
        val downloads = java.io.File(System.getProperty("user.home"), "Downloads")
        assertEquals(if (downloads.isDirectory) downloads.path else System.getProperty("user.home"), directory, "The first export proposes the Downloads")
        assertEquals("zsh 2026-11-10 19-43.html", name)
        val file = folder.resolve("history.html")
        awaitTrue("the file") { java.nio.file.Files.exists(file) }
        val page = java.nio.file.Files.readString(file)
        assertTrue(page.startsWith("<!DOCTYPE html>") && "hello export" in page)
        assertEquals(ExportFormat.HTML, controller.lastExportFormat, "The next export starts on it")

        // The next one proposes the folder of the last.
        saveAnswer = null
        controller.exportHistory(id)
        controller.answerExport(ExportFormat.TEXT, now)
        assertEquals(folder.toString() to "zsh 2026-11-10 19-43.txt", saveRequests.last())
    }

    @Test
    fun aSavePanelThatIsCancelledWritesNothing() {
        terminalThatWrote("hello")
        val folder = java.nio.file.Files.createTempDirectory("katty-export")

        controller.exportHistory()
        controller.answerExport(ExportFormat.TEXT, now)

        assertEquals(1, saveRequests.size)
        assertEquals(emptyList(), folder.toFile().list()!!.toList())
        assertNull(controller.exportFailure)
    }

    @Test
    fun aFileThatCannotBeWrittenIsTold() {
        terminalThatWrote("hello")
        saveAnswer = "/no/such/folder/history.txt"

        controller.exportHistory()
        controller.answerExport(ExportFormat.TEXT, now)

        awaitTrue("the failure") { controller.exportFailure != null }
        assertEquals("/no/such/folder/history.txt could not be written: its folder is not there.", controller.exportFailure)
        assertTrue(controller.blockingDialog, "It is told in a dialog")
    }

    @Test
    fun thereIsNothingToExportOnTheBoardNorForATerminalWithoutASession() {
        controller.exportHistory()
        assertNull(controller.exportingHistory, "No session")

        terminalThatWrote("hello")
        showBoard()
        controller.paletteHandlers().exportHistory()
        assertNull(controller.exportingHistory, "The palette's terminal commands are for the terminals")
    }
}
