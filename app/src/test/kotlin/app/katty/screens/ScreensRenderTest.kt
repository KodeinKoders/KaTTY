package app.katty.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.katty.core.KattyStore
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

class ScreensRenderTest {
    private fun store(): KattyStore {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/Users/me/dev/web-client")
        store.addProject("/Users/me/dev/docs-site")
        val gateway = store.addProject("/Users/me/dev/api-gateway")
        store.renameProject(gateway, "api-gateway")
        store.renameWorkspace(store.state.value.selection.workspace!!, "refactor-auth")
        store.addWorkspace(gateway, "rate-limiter")
        store.addWorkspace(gateway, "release-notes")
        store.selectWorkspace(store.state.value.projects.last().workspaces.first().id)
        return store
    }

    @Composable
    private fun SidebarOf(store: KattyStore) {
        val state by store.state.collectAsState()
        Sidebar(state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null, onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier)
    }

    @Test
    fun drawsTheSidebarInBothThemes() {
        val light = render("sidebar-light", 560, 900) { SidebarOf(store()) }
        val dark = render("sidebar-dark", 560, 900, dark = true) { SidebarOf(store()) }

        // The selected workspace is drawn in the color of a selection.
        assertTrue(light.count(0xD4E2FF) > 1000)
        assertTrue(dark.count(0x2E436E) > 1000)
    }

    @Test
    fun drawsTheWelcomeInBothThemes() {
        val light = render("welcome-light", 1440, 900) { Welcome(onAddProject = {}, modifier = Modifier.fillMaxSize()) }
        val dark = render("welcome-dark", 1440, 900, dark = true) { Welcome(onAddProject = {}, modifier = Modifier.fillMaxSize()) }

        assertTrue(light.count(0xF2F3F5) > 100_000)
        assertTrue(dark.count(0x1E1F22) > 100_000)
    }

    @Test
    fun drawsTheQuestionOfTheIdeOfAFileInBothThemes() {
        val file = app.katty.terminal.TerminalFilePosition("/Users/me/dev/api-gateway/src/main/kotlin/RateLimiter.kt", 42, 5)
        val ides = listOf(app.katty.core.Ide.VS_CODE, app.katty.core.Ide.INTELLIJ_IDEA, app.katty.core.Ide.XCODE, app.katty.core.Ide.FINDER)
        val light = render("open-file-light", 1000, 700) { OpenFileDialog(file, "refactor-auth", ides, onOpen = { _, _ -> }, onCancel = {}) }
        val dark = render("open-file-dark", 1000, 700, dark = true) { OpenFileDialog(file, "refactor-auth", ides, onOpen = { _, _ -> }, onCancel = {}) }

        // The row that the keys are on.
        assertTrue(light.count(0xD4E2FF) > 1000)
        assertTrue(dark.count(0x2E436E) > 1000)
    }
}
