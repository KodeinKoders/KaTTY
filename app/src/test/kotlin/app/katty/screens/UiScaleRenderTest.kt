package app.katty.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.katty.core.KattyStore
import app.katty.core.LayoutMode
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.Settings
import app.katty.ui.UiScale
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The screens at the usual size and at the biggest one, drawn in `build/renders` (`ui-*-100.png` and `ui-*-175.png`): look at them when the
 * text of a row or a header is made to grow, since a row that cannot grow cuts what is in it.
 */
class UiScaleRenderTest {
    private fun store(): KattyStore {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/Users/me/dev/web-client")
        val gateway = store.addProject("/Users/me/dev/api-gateway")
        store.renameWorkspace(store.state.value.selection.workspace!!, "refactor-auth")
        store.addWorkspace(gateway, "rate-limiter")
        return store
    }

    @Composable
    private fun SidebarOf(store: KattyStore) {
        val state by store.state.collectAsState()
        Sidebar(state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null, onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier)
    }

    private val entries = listOf(
        PaletteEntry("w1", PaletteGroup.WORKSPACES, "refactor-auth", "api-gateway"),
        PaletteEntry("t1", PaletteGroup.TERMINALS, "Claude Code", "refactor-auth  ·  working"),
        PaletteEntry("c1", PaletteGroup.COMMANDS, "Split right", shortcut = "⌘D"),
        PaletteEntry("c2", PaletteGroup.COMMANDS, "Close terminal", shortcut = "⌘W"),
    )

    private fun draws(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
            val rendered = render("ui-$name-$suffix", width, height) { UiScale(scale) { content() } }
            assertTrue(rendered.count(0xFFFFFF) + rendered.count(0xF2F3F5) > 100, "$name at $suffix is drawn")
        }
    }

    @Test
    fun drawsTheSidebar() = draws("sidebar", 560, 700) { SidebarOf(store()) }

    @Test
    fun drawsThePalette() = draws("palette", 1000, 700) { CommandPalette(entries, onRun = {}, onDismiss = {}) }

    @Test
    fun drawsTheMainMenu() = draws("menu", 700, 1300) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            MainMenu(Settings(layout = LayoutMode.HORIZONTAL), onChange = {}, colorScheme = null, fontFamilies = emptyList(), onPickColorScheme = {}, onDismiss = {})
        }
    }
}
