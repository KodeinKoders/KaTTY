package app.katty.screens

import app.katty.AppShortcut
import app.katty.core.Ide
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.Workspace
import app.katty.core.WorkspaceId
import app.katty.ui.MenuBuilder
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import app.katty.ui.KattyMenu
import app.katty.ui.MenuEntry
import app.katty.ui.menu
import app.katty.ui.onSecondaryClick

/**
 * What the projects and workspaces of a list need of the environments: the ones that exist ([available]), for their badges and menus, and what
 * changes them. [onMenu] reads the folders again when a menu that lists them opens.
 */
class EnvironmentControls(
    val available: Set<String> = emptySet(),
    val onMenu: () -> Unit = {},
    val setProject: (ProjectId, String?) -> Unit = { _, _ -> },
    val setWorkspace: (WorkspaceId, String?) -> Unit = { _, _ -> },
    val useProjectEnvironment: (WorkspaceId) -> Unit = {},
    val openFolder: () -> Unit = {},
)

/**
 * The environment of a project or a workspace in its menu: [current] (`null` for none), the ones that exist ([names]), and what [onChoose]s one
 * or opens the folder they are in.
 */
class EnvironmentMenu(val current: String?, val names: Set<String>, val onChoose: (String?) -> Unit, val onOpenFolder: () -> Unit)

/** The environment of [project] in its menu, with [controls]. */
fun EnvironmentControls.menuOf(project: Project): EnvironmentMenu =
    EnvironmentMenu(project.environment, available, { setProject(project.id, it) }, openFolder)

/** The environment of [workspace] in its menu, with [controls]. */
fun EnvironmentControls.menuOf(workspace: Workspace): EnvironmentMenu =
    EnvironmentMenu(workspace.environment, available, { setWorkspace(workspace.id, it) }, openFolder)

/**
 * "Environment ›": "None", then the environments, the one in use checked (one that has no file says so), and the folder they are in. The current
 * one is the hint of the item.
 */
private fun MenuBuilder.environmentSubmenu(environment: EnvironmentMenu) {
    val current = environment.current
    submenu("Environment", hint = current ?: "None") {
        action("None", checked = current == null) { environment.onChoose(null) }
        val names = (environment.names + listOfNotNull(current)).sorted()
        if (names.isNotEmpty()) divider()
        for (name in names) {
            action(if (name in environment.names) name else "$name (not found)", checked = name == current) { environment.onChoose(name) }
        }
        divider()
        action("Open environments folder", onClick = environment.onOpenFolder)
    }
}

/** The menu of a project, wherever it is listed: the sidebar, or the tabs of the vertical layout. */
fun projectMenu(
    onNewWorkspace: () -> Unit,
    onRename: () -> Unit,
    onReveal: () -> Unit,
    onRemove: () -> Unit,
    ides: List<Ide> = emptyList(),
    onOpenIn: (Ide) -> Unit = {},
    environment: EnvironmentMenu? = null,
): List<MenuEntry> = menu {
    action("New workspace", AppShortcut.NEW_WORKSPACE.label, onClick = onNewWorkspace)
    action("Rename project…", "F2", onClick = onRename)
    action("Reveal in file manager", onClick = onReveal)
    if (ides.isNotEmpty()) submenu("Open in") { for (ide in ides) action(ide.displayName) { onOpenIn(ide) } }
    if (environment != null) environmentSubmenu(environment)
    divider()
    action("Remove project…", AppShortcut.REMOVE_PROJECT.label, danger = true, onClick = onRemove)
}

/**
 * The menu of a workspace. It has no split: splitting acts on a terminal, so it is in the menus of the terminals and in the header.
 *
 * @param onDefaultIde Chooses the IDE that opens the files whose paths are clicked in its terminals; `null` when no IDE is installed.
 * @param onRun Asks for a command to run in its run terminal; `null` when there is none to ask.
 * @param environment Its environment, which starts its shells again when it changes; `null` for no item.
 * @param projectEnvironment The environment of its project, when it is not the workspace's: "Use project environment" gives it to the workspace.
 * @param onUseProjectEnvironment What that item does.
 */
fun workspaceMenu(
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDefaultIde: (() -> Unit)? = null,
    onRun: (() -> Unit)? = null,
    environment: EnvironmentMenu? = null,
    projectEnvironment: String? = null,
    onUseProjectEnvironment: (() -> Unit)? = null,
): List<MenuEntry> = menu {
    if (onRun != null) action("Run a command…", AppShortcut.RUN.label, onClick = onRun)
    action("Rename workspace", "F2", onClick = onRename)
    if (onDefaultIde != null) action("Default IDE…", onClick = onDefaultIde)
    if (environment != null) {
        environmentSubmenu(environment)
        if (onUseProjectEnvironment != null && projectEnvironment != environment.current) {
            action("Use project environment", hint = projectEnvironment ?: "None", onClick = onUseProjectEnvironment)
        }
    }
    divider()
    action("Close workspace", AppShortcut.CLOSE_WORKSPACE.label, danger = true, onClick = onClose)
}

/** The menu of the buttons that add a project: the folder chooser, or the list of the projects opened before. */
fun addProjectMenu(onAdd: () -> Unit, onOpenRecent: () -> Unit): List<MenuEntry> = menu {
    action("Add project folder…", AppShortcut.ADD_PROJECT.label, onClick = onAdd)
    action("Open recent project…", AppShortcut.OPEN_RECENT.label, onClick = onOpenRecent)
}

/** Shows [addProjectMenu] on a right click on what [content] draws: the button that adds a project, wherever it is. */
@Composable
fun AddProjectMenuHost(onAdd: () -> Unit, onOpenRecent: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier.onSecondaryClick { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }) {
        content()
        menuAt?.let { at -> KattyMenu(addProjectMenu(onAdd, onOpenRecent), onDismiss = { menuAt = null }, offset = at) }
    }
}
