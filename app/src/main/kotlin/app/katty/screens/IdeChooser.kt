package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.Ide
import app.katty.terminal.TerminalFilePosition
import app.katty.ui.CheckSetting
import app.katty.ui.DialogScrim
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.ListKey
import app.katty.ui.OneLine
import app.katty.ui.hoverHighlight
import app.katty.ui.listKey
import app.katty.ui.stepped
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.io.File

/** What the row of no IDE says, in the IDE of a workspace. */
internal const val ASK_EVERY_TIME = "Ask every time"

/**
 * Asked when the path of a file or a folder is clicked in a terminal of a workspace that has no IDE: which one opens it. Choosing an IDE (a click, or the
 * arrows and Enter) opens the file in it, and with the box checked the workspace keeps it, so that its next files open in it at once; the
 * workspace's menu changes it. The Finder is among them. Escape, or a click outside the dialog, cancels.
 */
@Composable
fun OpenFileDialog(file: TerminalFilePosition, workspace: String, ides: List<Ide>, onOpen: (ide: Ide, remember: Boolean) -> Unit, onCancel: () -> Unit) {
    var keep by remember { mutableStateOf(false) }
    IdeDialog(
        title = "Open ${fileLabel(file)} in…",
        message = homeShortened(file.path),
        options = ides,
        current = null,
        onChoose = { ide -> if (ide != null) onOpen(ide, keep) },
        onCancel = onCancel,
    ) {
        CheckSetting("Always use it in “$workspace”", keep, { keep = it })
    }
}

/**
 * The IDE of a workspace, from its menu: the one that opens the files whose paths are clicked in its terminals, or none, and the user is then
 * asked at each file. The one it has is checked; choosing one sets it at once.
 */
@Composable
fun WorkspaceIdeDialog(workspace: String, ides: List<Ide>, current: Ide?, onChoose: (Ide?) -> Unit, onCancel: () -> Unit) {
    IdeDialog(
        title = "Default IDE of “$workspace”",
        message = "It opens the files and folders whose paths you click in its terminals with ⌘.",
        options = listOf(null) + ides,
        current = current,
        onChoose = onChoose,
        onCancel = onCancel,
    )
}

/** `Foo.kt:42`: the name of the file, and its line; `src/` for a folder. */
internal fun fileLabel(file: TerminalFilePosition): String =
    File(file.path).name + if (file.directory) "/" else file.line?.let { ":$it" } ?: ""

private fun homeShortened(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return if (home.isNotEmpty() && path.startsWith("$home/")) "~" + path.removePrefix(home) else path
}

// A list of IDEs (`null` is none) over the scrim: the keys move in it and choose wherever the focus is in the dialog, and it starts on the one that
// the workspace has.
@Composable
private fun IdeDialog(
    title: String,
    message: String,
    options: List<Ide?>,
    current: Ide?,
    onChoose: (Ide?) -> Unit,
    onCancel: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = Katty.colors
    var highlighted by remember { mutableIntStateOf(options.indexOf(current).coerceAtLeast(0)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            when (event.listKey()) {
                ListKey.UP -> { highlighted = highlighted.stepped(-1, options.size); true }
                ListKey.DOWN -> { highlighted = highlighted.stepped(1, options.size); true }
                ListKey.ENTER -> { if (highlighted in options.indices) onChoose(options[highlighted]); true }
                ListKey.ESCAPE -> { onCancel(); true }
                null -> false
            }
        },
        width = 440.dp * uiScaleFactor(),
        onDismiss = onCancel,
    ) {
        Column(Modifier.semantics { contentDescription = title }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                OneLine(message, color = colors.muted)
            }
            Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().focusRequester(focus).focusable()) {
                options.forEachIndexed { index, ide ->
                    // The one that the workspace has; no row is checked in a list that has no row of none and is not given one.
                    IdeRow(ide?.displayName ?: ASK_EVERY_TIME, highlighted = index == highlighted, checked = ide == current) { onChoose(ide) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f)) { footer?.invoke() }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun IdeRow(label: String, highlighted: Boolean, checked: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(6.dp))
            .hoverHighlight(enabled = !highlighted)
            .background(if (highlighted) colors.sel else Color.Transparent)
            .semantics { contentDescription = label; selected = checked }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OneLine(label, Modifier.weight(1f), color = colors.text)
        if (checked) Icon(KattyIcons.Check, colors.accent, size = 14.dp) else Spacer(Modifier.size(14.dp))
    }
}
