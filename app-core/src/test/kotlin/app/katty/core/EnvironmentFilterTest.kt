package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentFilterTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    // A project with two workspaces, one in `kodein` and one in `sunday`, and one with a workspace that has no environment.
    private fun twoProjects(): Pair<Project, Project> {
        val api = store.addProject("/dev/api")
        store.setWorkspacesEnvironment(state.projects.single { it.id == api }.workspaces.map { it.id }.toSet(), "kodein")
        store.addWorkspace(api)
        val second = state.projects.single { it.id == api }.workspaces.last()
        store.setWorkspacesEnvironment(setOf(second.id), "sunday")
        val web = store.addProject("/dev/web")
        return state.projects.single { it.id == api } to state.projects.single { it.id == web }
    }

    @Test
    fun aHiddenEnvironmentIsNotShownAndTheOthersAre() {
        val filter = EnvironmentFilter().toggled("sunday")

        assertFalse(filter.shows("sunday"))
        assertTrue(filter.shows("kodein"))
        assertTrue(filter.shows(null), "Standard is its own box")
        assertFalse(filter.toggled(null).shows(null))
        assertTrue(filter.toggled("sunday").shows("sunday"), "Checked again")
    }

    @Test
    fun theDotIsOnlyForAnEnvironmentThatIsListed() {
        val filter = EnvironmentFilter(hidden = setOf("gone"))

        assertFalse(filter.isActive(setOf("kodein")), "A name that nothing has hides nothing")
        assertTrue(filter.isActive(setOf("kodein", "gone")))
        assertTrue(EnvironmentFilter(standardHidden = true).isActive(emptySet()))
        assertFalse(EnvironmentFilter.NONE.isActive(setOf("kodein")))
    }

    @Test
    fun everythingIsHiddenWhenStandardAndEveryListedEnvironmentAre() {
        val names = setOf("kodein", "sunday")

        assertTrue(EnvironmentFilter(hidden = names, standardHidden = true).hidesAll(names))
        assertFalse(EnvironmentFilter(hidden = names).hidesAll(names), "Standard is shown")
        assertFalse(EnvironmentFilter(hidden = setOf("kodein"), standardHidden = true).hidesAll(names))
    }

    @Test
    fun hidingAllHidesStandardAndEveryListedEnvironmentAndKeepsTheOthers() {
        val names = setOf("kodein", "sunday")
        val filter = EnvironmentFilter(hidden = setOf("gone")).hidingAll(names)

        assertTrue(filter.hidesAll(names))
        assertEquals(setOf("gone", "kodein", "sunday"), filter.hidden, "A name that is not listed stays hidden")
        assertEquals(EnvironmentFilter.NONE, EnvironmentFilter.NONE, "Show all is the filter that shows everything")
    }

    @Test
    fun theListHasTheEnvironmentsThatExistAndTheOnesThatAreUsedWithoutAFile() {
        twoProjects()

        assertEquals(setOf("kodein", "sunday", "perso"), state.environmentNames(setOf("perso")))
        assertEquals(setOf("kodein", "sunday"), state.environmentNames(emptySet()), "Used, with no file")
    }

    @Test
    fun aProjectIsListedWhileOneOfItsWorkspacesIs() {
        val (api, web) = twoProjects()

        assertEquals(listOf("kodein"), api.shownWorkspaces(EnvironmentFilter(hidden = setOf("sunday"))).map { it.environment })
        assertEquals(listOf(api.id, web.id), state.shownProjects(EnvironmentFilter.NONE).map { it.id })
        // `web` has no environment: it is Standard.
        assertEquals(listOf(api.id), state.shownProjects(EnvironmentFilter(standardHidden = true)).map { it.id })
        assertEquals(listOf(web.id), state.shownProjects(EnvironmentFilter(hidden = setOf("kodein", "sunday"))).map { it.id })
        assertEquals(emptyList(), state.shownProjects(EnvironmentFilter(hidden = setOf("kodein", "sunday"), standardHidden = true)))
    }

    private fun filter(vararg hidden: String, standard: Boolean = false) = EnvironmentFilter(hidden.toSet(), standard)

    private fun hide(filter: EnvironmentFilter) = store.updateSettings { it.copy(environmentFilter = filter) }

    @Test
    fun theNearestShownWorkspaceIsTheNextOneWhenTwoAreAsNear() {
        val (api, web) = twoProjects()
        val (kodein, sunday) = api.workspaces
        val standard = web.workspaces.single()
        // In the order of the sidebar: kodein, sunday, standard.

        assertEquals(sunday.id, state.nearestShownWorkspace(filter("kodein"), kodein.id), "The next, in the same project")
        assertEquals(standard.id, state.nearestShownWorkspace(filter("sunday", "kodein"), sunday.id), "In another project")
        assertEquals(kodein.id, state.nearestShownWorkspace(filter("sunday", standard = true), standard.id), "Before, when nothing is after")
        assertEquals(kodein.id, state.nearestShownWorkspace(filter("sunday"), kodein.id), "Itself when it is shown")
        assertEquals(null, state.nearestShownWorkspace(filter("kodein", "sunday", standard = true), kodein.id))
        // With one before and one after at the same distance, the one after.
        val third = store.addWorkspace(api.id, "third")!!
        store.setWorkspacesEnvironment(setOf(third), "perso")
        assertEquals(third, state.nearestShownWorkspace(filter("sunday"), sunday.id))
    }

    @Test
    fun hidingTheEnvironmentOfTheSelectedWorkspaceSelectsTheNearestOne() {
        val (api, web) = twoProjects()
        store.selectWorkspace(api.workspaces.first().id)

        hide(filter("kodein"))

        assertEquals(api.workspaces.last().id, state.selection.workspace, "The next one")
        assertEquals(api.id, state.selection.project)
        assertEquals(api.workspaces.last().layout.terminals().first().id, state.selection.terminal)

        hide(filter("kodein", "sunday"))

        assertEquals(web.workspaces.single().id, state.selection.workspace, "In another project")
        assertEquals(web.id, state.selection.project)
    }

    @Test
    fun theSelectionStaysWhenTheFilterShowsNoWorkspace() {
        val (api, _) = twoProjects()
        store.selectWorkspace(api.workspaces.first().id)
        val before = state.selection

        hide(filter("kodein", "sunday", standard = true))

        assertEquals(before, state.selection)
        assertEquals(emptyList(), state.shownProjects(state.settings.environmentFilter))

        hide(EnvironmentFilter.NONE)
        assertEquals(before, state.selection, "Show all is where the user was")
    }

    @Test
    fun changingTheEnvironmentOfTheSelectedWorkspaceThatTheFilterHidesMovesTheSelection() {
        val (api, web) = twoProjects()
        hide(filter("sunday"))
        store.selectWorkspace(api.workspaces.first().id)

        store.setWorkspacesEnvironment(setOf(api.workspaces.first().id), "sunday")

        assertEquals(web.workspaces.single().id, state.selection.workspace)
    }

    @Test
    fun whatIsMadeIsShownEvenWhenItsEnvironmentWasHidden() {
        val (api, _) = twoProjects()
        hide(filter("kodein", "sunday", standard = true))

        // A workspace made in a project that has no environment has none: it is Standard.
        val added = store.addWorkspace(api.id, "new")!!

        assertEquals(added, state.selection.workspace)
        assertEquals(filter("kodein", "sunday"), state.settings.environmentFilter)

        hide(filter("kodein", "sunday", standard = true))
        val project = store.addProject("/dev/other")

        assertEquals(project, state.selection.project)
        assertEquals(filter("kodein", "sunday"), state.settings.environmentFilter)
    }

    @Test
    fun theNavigationDoesNotGoToAHiddenWorkspace() {
        val (api, web) = twoProjects()
        val kodein = api.workspaces.first()
        store.selectWorkspace(kodein.id)
        hide(filter("sunday"))

        assertEquals(web.workspaces.single().id, state.workspaceStep(1), "Past `sunday`")
        assertEquals(web.workspaces.single().id, state.workspaceStep(-1))
        assertEquals(kodein.id, state.workspaceByNumber(1))
        assertEquals(kodein.id, state.workspaceByNumber(9), "The last one of the project is the last that is shown")
        assertEquals(web.id, state.projectStep(1))

        hide(filter("kodein", "sunday"))
        assertEquals(web.id, state.projectStep(1), "Only `web` is left")
    }

    @Test
    fun theBellAndTheNextTerminalThatNeedsTheUserIgnoreWhatIsHidden() {
        val (api, _) = twoProjects()
        val kodein = api.workspaces.first().layout.terminals().first().id
        val sunday = api.workspaces.last().layout.terminals().first().id
        val infos = mapOf(
            kodein to AgentInfo(AgentReport(AgentState.NEEDS_INPUT, "claude-code"), 1),
            sunday to AgentInfo(AgentReport(AgentState.NEEDS_INPUT, "claude-code"), 2),
        )

        assertEquals(setOf(kodein, sunday), state.attention(infos).needsYou.map { it.terminal.id }.toSet())

        hide(filter("sunday"))

        val attention = state.attention(infos)
        assertEquals(listOf(kodein), attention.needsYou.map { it.terminal.id })
        assertEquals(kodein, attention.next(state, null))
    }

    @Test
    fun theBoardLeavesOutTheCardsOfAHiddenEnvironment() {
        val (api, _) = twoProjects()
        val kodein = api.workspaces.first().layout.terminals().first().id
        val sunday = api.workspaces.last().layout.terminals().first().id
        val infos = mapOf(
            kodein to AgentInfo(AgentReport(AgentState.WORKING, "claude-code"), 1),
            sunday to AgentInfo(AgentReport(AgentState.WORKING, "claude-code"), 2),
        )

        fun ids(filter: EnvironmentFilter, project: ProjectId? = null) =
            state.board(project, infos, filter).values.flatten().map { it.terminal.id }

        assertEquals(listOf(kodein, sunday), ids(EnvironmentFilter.NONE))
        assertEquals(listOf(kodein), ids(EnvironmentFilter(hidden = setOf("sunday"))))
        assertEquals(emptyList(), ids(EnvironmentFilter(hidden = setOf("sunday", "kodein"))))
        assertEquals(listOf(sunday), ids(EnvironmentFilter(hidden = setOf("kodein")), project = api.id))
    }

    @Test
    fun theBoardListsOnlyTheProjectsWithAShownWorkspace() {
        val (api, web) = twoProjects()

        assertEquals(listOf(api.id, web.id), state.shownProjects(EnvironmentFilter.NONE).map { it.id })
        assertEquals(listOf(api.id), state.shownProjects(EnvironmentFilter(standardHidden = true)).map { it.id })
        assertEquals(listOf(web.id), state.shownProjects(EnvironmentFilter(hidden = setOf("kodein", "sunday"))).map { it.id })
    }

    @Test
    fun theCountsOfAProjectOnlyHaveTheWorkspacesThatAreShown() {
        val (api, _) = twoProjects()
        val states = object : AgentStates { override fun stateOf(id: TerminalId) = AgentState.WORKING }

        assertEquals(listOf(AgentState.WORKING to 2), states.counts(api))
        assertEquals(listOf(AgentState.WORKING to 1), states.counts(api, EnvironmentFilter(hidden = setOf("sunday"))))
        assertEquals(emptyList(), states.counts(api, EnvironmentFilter(hidden = setOf("sunday", "kodein"))))
    }

    @Test
    fun theFilterIsKeptInTheSettings() {
        store.updateSettings { it.copy(environmentFilter = EnvironmentFilter(hidden = setOf("sunday"), standardHidden = true)) }
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }

        val read = json.decodeFromString<Settings>(json.encodeToString(Settings.serializer(), state.settings))

        assertEquals(EnvironmentFilter(hidden = setOf("sunday"), standardHidden = true), read.environmentFilter)
        assertEquals(EnvironmentFilter.NONE, json.decodeFromString<Settings>("{}").environmentFilter, "A file from before has none")
    }
}
