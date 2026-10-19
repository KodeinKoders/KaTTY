package app.katty.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import org.jetbrains.jewel.ui.component.Text

/**
 * Where something is in git, as the header of a pane and the workspaces say it: the icon of a branch (of a linked working tree, for one), and
 * its name, in the font of a terminal and in the muted color, on one line that is cut short when there is not room.
 */
@Composable
internal fun GitLabel(git: GitInfo, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    val colors = Katty.colors
    val description = if (git.kind == GitKind.WORKTREE) "Worktree" else if (git.kind == GitKind.DETACHED) "Commit" else "Branch"
    Row(
        modifier.semantics { contentDescription = "$description ${git.name}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (git.kind == GitKind.WORKTREE) KattyIcons.Worktree else KattyIcons.Branch, colors.muted, size = 12.dp)
        Text(git.name, color = colors.muted, fontSize = fontSize, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
