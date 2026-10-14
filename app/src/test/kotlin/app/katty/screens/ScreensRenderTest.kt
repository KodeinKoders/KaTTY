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
        Sidebar(state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier)
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
}
