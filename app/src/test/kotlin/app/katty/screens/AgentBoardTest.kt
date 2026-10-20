@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.BoardColumn
import app.katty.core.KattyStore
import app.katty.core.ProjectId
import app.katty.core.SessionUsage
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.board
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentBoardTest {
    private val now = 10_000_000_000L
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })

    // A project whose first workspace is called "workspace", which is not what a new project's is: that has its project's name.
    private fun plainProject(directory: String): app.katty.core.ProjectId {
        val id = store.addProject(directory)
        store.renameWorkspace(store.state.value.selection.workspace!!, "workspace")
        return id
    }
    private val state get() = store.state.value
    private lateinit var infos: Map<TerminalId, AgentInfo>

    private fun info(s: AgentState, app: String?, ago: Long, note: String?) = AgentInfo(AgentReport(s, app, note), now - ago * 60_000)

    private fun populate() {
        val gateway = plainProject("/dev/api-gateway")
        store.renameWorkspace(state.selection.workspace!!, "refactor-auth")
        val claude = state.selection.terminal!!
        val codex = store.split(claude, SplitDirection.ROW)!!
        val shell = store.split(codex, SplitDirection.ROW)!!
        plainProject("/dev/web-client")
        val failed = state.selection.terminal!!
        infos = mapOf(
            claude to info(AgentState.WORKING, "claude-code", 4, "Running the auth tests to check the refactor."),
            codex to info(AgentState.NEEDS_INPUT, "codex", 2, "Apply this patch? 1. Yes  2. No, tell me what to change"),
            shell to info(AgentState.IDLE, null, 35, null),
            failed to info(AgentState.ERROR, "claude-code", 14, "Failed: build error in CartSummary.tsx, waiting for a decision."),
        )
        check(gateway.value.isNotEmpty())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(vertical: Boolean = false, filter: ProjectId? = null, onOpen: (TerminalId) -> Unit = {}) {
        setContent { KattyTheme(false) { AgentBoard(state.board(filter, infos), emptyMap(), now, vertical, onOpen) } }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: androidx.compose.ui.input.key.Key) {
        onNodeWithText("Agent board").performKeyInput { pressKey(key) }
    }

    @Test
    fun theArrowsMoveFromCardToCardAndEnterOpensItsTerminal() = runComposeUiTest {
        populate()
        val opened = mutableListOf<TerminalId>()
        show(onOpen = { opened += it })
        waitForIdle()
        val columns = state.board(null, infos)
        val failed = columns.getValue(BoardColumn.NEEDS_INPUT)[0].terminal.id
        val codex = columns.getValue(BoardColumn.NEEDS_INPUT)[1].terminal.id

        // The first arrow selects the first card of the board, the one that works; then right is the column of the ones that need the user.
        press(Key.DirectionDown)
        waitForIdle()
        onNodeWithContentDescription("Claude Code in api-gateway / refactor-auth").assertIsSelected()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf(codex), opened)
        assertTrue(failed != codex)
    }

    @Test
    fun enterWithNoCardSelectedOpensNothing() = runComposeUiTest {
        populate()
        val opened = mutableListOf<TerminalId>()
        show(onOpen = { opened += it })
        waitForIdle()

        press(Key.Enter)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun theArrowsWorkWhenTheColumnsAreStackedToo() = runComposeUiTest {
        populate()
        val opened = mutableListOf<TerminalId>()
        show(vertical = true, onOpen = { opened += it })
        waitForIdle()
        val columns = state.board(null, infos)

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf(columns.getValue(BoardColumn.NEEDS_INPUT)[1].terminal.id), opened)
    }

    @Test
    fun hasAColumnForEachStateWithItsCount() = runComposeUiTest {
        populate()
        show()

        onNodeWithText("Agent board").assertExists()
        onNodeWithText("3 agents across 2 projects").assertExists()
        onNodeWithText("Nothing").assertExists()
        onNodeWithText("Working").assertExists()
        onNodeWithText("Needs input").assertExists()
        onNodeWithText("Done").assertExists()
    }

    @Test
    fun aCardSaysWhatRunsWhereSinceWhenAndWhatItSaid() = runComposeUiTest {
        populate()
        show()

        onNodeWithContentDescription("Claude Code in api-gateway / refactor-auth").assertExists()
        onNodeWithText("4 min").assertExists()
        onNodeWithText("Running the auth tests to check the refactor.").assertExists()
        onNodeWithText("Apply this patch? 1. Yes  2. No, tell me what to change").assertExists()
    }

    @Test
    fun aFailureIsInTheColumnOfWhatNeedsTheUser() = runComposeUiTest {
        populate()
        show()

        onNodeWithContentDescription("Claude Code in web-client / workspace").assertExists()
        onNodeWithText("Failed: build error in CartSummary.tsx, waiting for a decision.").assertExists()
    }

    @Test
    fun aShellAtItsPromptHasNoCard() = runComposeUiTest {
        populate()
        show()

        // The board is for the agents: a terminal that runs a shell is not on it.
        onNodeWithText("Idle shell, waiting at the prompt.").assertDoesNotExist()
        onNodeWithContentDescription("shell in api-gateway / refactor-auth").assertDoesNotExist()
        onNodeWithContentDescription("Claude Code in api-gateway / refactor-auth").assertExists()
    }

    @Test
    fun theHeaderHasTheButtonThatOpensTheBoardInAWindowOnlyWhenItIsGivenOne() = runComposeUiTest {
        populate()
        var opened = 0
        var withButton by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            KattyTheme(false) {
                AgentBoard(state.board(null, infos), emptyMap(), now, false, onOpen = {}, onOpenWindow = if (withButton) ({ opened++ }) else null)
            }
        }

        onNodeWithContentDescription("Open the board in a window").performClick()
        assertEquals(1, opened)
        // In the window itself there is nothing to open.
        withButton = false
        waitForIdle()
        onNodeWithContentDescription("Open the board in a window").assertDoesNotExist()
    }

    @Test
    fun aProgramAtRestAfterBeingSeenIsNotCalledAShell() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        val seen = state.selection.terminal!!
        infos = mapOf(seen to AgentInfo(AgentReport(AgentState.IDLE, reporting = true), now - 3 * 60_000))
        setContent { KattyTheme(false) { AgentBoard(state.board(null, infos), emptyMap(), now, false, onOpen = {}) } }

        onNodeWithText("At rest.").assertExists()
        onNodeWithText("Idle shell, waiting at the prompt.").assertDoesNotExist()
        onNodeWithText("3 min").assertExists()
    }

    @Test
    fun aProgramThatIsNotAKnownAgentHasACardTitledByItsName() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        val tool = state.selection.terminal!!
        infos = mapOf(tool to AgentInfo(AgentReport(AgentState.WORKING, "my-tool", "Compiling"), now - 60_000))
        setContent { KattyTheme(false) { AgentBoard(state.board(null, infos), emptyMap(), now, false, onOpen = {}) } }

        onNodeWithText("my-tool").assertExists()
        onNodeWithText("Compiling").assertExists()
    }

    @Test
    fun clickingACardOpensItsTerminal() = runComposeUiTest {
        populate()
        val opened = mutableListOf<TerminalId>()
        show(onOpen = { opened += it })

        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").performClick()

        assertEquals(1, opened.size)
        assertEquals("codex", infos.getValue(opened.single()).report.app)
    }

    @Test
    fun theFilterKeepsOneProject() = runComposeUiTest {
        populate()
        show(filter = state.projects.last().id)

        onNodeWithText("1 agent across 1 project").assertExists()
        onNodeWithContentDescription("Claude Code in web-client / workspace").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Codex in api-gateway / refactor-auth").fetchSemanticsNodes().size)
    }

    @Test
    fun theVerticalBoardStacksTheSectionsAndHasTheSameCards() = runComposeUiTest {
        populate()
        show(vertical = true)

        onNodeWithContentDescription("Working").assertExists()
        onNodeWithContentDescription("Needs input").assertExists()
        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").assertExists()
    }

    @Test
    fun theProjectListFiltersTheBoard() = runComposeUiTest {
        populate()
        val chosen = mutableListOf<ProjectId?>()
        setContent { KattyTheme(false) { BoardSidebar(state, filter = null, onFilter = { chosen += it }) } }

        onNodeWithContentDescription("All projects").assertIsSelected()
        onNodeWithText("4 agents").assertExists()
        onNodeWithContentDescription("web-client").performClick()

        assertEquals(listOf<ProjectId?>(state.projects.last().id), chosen)
    }

    @Test
    fun theTabsOfTheVerticalLayoutFilterTheBoardToo() = runComposeUiTest {
        populate()
        val chosen = mutableListOf<ProjectId?>()
        setContent { KattyTheme(false) { BoardTabs(state, filter = state.projects.first().id, onFilter = { chosen += it }) } }

        onNodeWithContentDescription("api-gateway").assertIsSelected()
        onNodeWithContentDescription("All projects").performClick()

        assertEquals(listOf<ProjectId?>(null), chosen)
    }

    @Test
    fun agentsAreCountedInTheSingularToo() {
        assertEquals("1 agent", agentCount(1))
        assertEquals("0 agents", agentCount(0))
        assertEquals("9 agents", agentCount(9))
    }

    @Test
    fun drawsTheBoardInBothThemesAndBothLayouts() {
        populate()
        for (dark in listOf(false, true)) for (vertical in listOf(false, true)) {
            val name = "board-${if (vertical) "vertical" else "horizontal"}-${if (dark) "dark" else "light"}"
            val rendered = render(name, if (vertical) 1000 else 2400, if (vertical) 1800 else 900, dark = dark) {
                AgentBoard(state.board(null, infos), emptyMap(), now, vertical, onOpen = {})
            }
            assertTrue(rendered.count(if (dark) 0xE5A93D else 0xA85F00) > 50, name)
        }
    }


    @Test
    fun aCardSaysWhatItsSessionHasUsedWhenItIsKnown() = runComposeUiTest {
        populate()
        val claude = state.board(null, infos).getValue(BoardColumn.WORKING).single().terminal.id
        setContent { KattyTheme(false) { AgentBoard(state.board(null, infos), emptyMap(), now, false, {}, usages = mapOf(claude to SessionUsage(63, 4.5))) } }

        onAllNodesWithContentDescription("63% of context used, ~$4.50 spent in this session").assertCountEquals(1)
        onAllNodesWithContentDescription("% of context used", substring = true).assertCountEquals(1)
    }
}
