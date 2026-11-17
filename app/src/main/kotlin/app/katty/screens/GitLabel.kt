package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.GitChanges
import app.katty.core.GitChangesDisplay
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import org.jetbrains.jewel.ui.component.Text

/** How the git labels say that a tree has changes (`Settings.gitChanges`), which the window gives to all of them. */
val LocalGitChangesDisplay = staticCompositionLocalOf { GitChangesDisplay.DOT }

/**
 * Where something is in git, as the header of a pane and the workspaces say it, on one line in the font of a terminal: the icon of a branch (of a
 * linked working tree, for one) and its name, in the muted color; then, when they are known, the changes that are not committed (a dot, or their
 * counts: [display]), how far the branch is ahead of its upstream and behind it, and what git is in the middle of. The name is what is cut short
 * when there is not room.
 */
@Composable
internal fun GitLabel(git: GitInfo, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp, display: GitChangesDisplay = LocalGitChangesDisplay.current) {
    val colors = Katty.colors
    val changes = git.status?.changes?.takeUnless { it.isEmpty }
    Row(
        modifier.semantics { contentDescription = gitDescription(git, display) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (git.kind == GitKind.WORKTREE) KattyIcons.Worktree else KattyIcons.Branch, colors.muted, size = 12.dp)
        Text(
            git.name, Modifier.weight(1f, fill = false), color = colors.muted, fontSize = fontSize, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (changes != null) {
            when (display) {
                GitChangesDisplay.DOT -> Box(Modifier.size(6.dp).background(colors.input, CircleShape))
                GitChangesDisplay.COUNTS -> for ((text, color) in counts(changes)) Piece(text, color, fontSize)
            }
        }
        git.status?.ahead?.takeIf { it > 0 }?.let { Piece("↑$it", colors.muted, fontSize) }
        git.status?.behind?.takeIf { it > 0 }?.let { Piece("↓$it", colors.muted, fontSize) }
        git.operation?.let { Piece(it.label, colors.input, fontSize, FontWeight.SemiBold) }
    }
}

// What the counts say, each with its color: only the ones that are not 0.
@Composable
private fun counts(changes: GitChanges): List<Pair<String, Color>> {
    val colors = Katty.colors
    return listOfNotNull(
        changes.staged.takeIf { it > 0 }?.let { "+$it" to colors.done },
        changes.modified.takeIf { it > 0 }?.let { "~$it" to colors.input },
        changes.untracked.takeIf { it > 0 }?.let { "?$it" to colors.muted },
        changes.conflicted.takeIf { it > 0 }?.let { "!$it" to colors.err },
    )
}

@Composable
private fun Piece(text: String, color: Color, fontSize: TextUnit, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = fontSize, fontFamily = FontFamily.Monospace, fontWeight = weight, maxLines = 1, softWrap = false)
}

/** What a git label says, for a screen reader: `Branch main, 2 staged, 1 modified, 3 ahead, rebasing`. */
internal fun gitDescription(git: GitInfo, display: GitChangesDisplay): String {
    val kind = when (git.kind) {
        GitKind.WORKTREE -> "Worktree"
        GitKind.DETACHED -> "Commit"
        GitKind.BRANCH -> "Branch"
    }
    val changes = git.status?.changes?.takeUnless { it.isEmpty }
    val parts = buildList {
        if (changes != null) {
            if (display == GitChangesDisplay.DOT) {
                add("uncommitted changes")
            } else {
                if (changes.staged > 0) add("${changes.staged} staged")
                if (changes.modified > 0) add("${changes.modified} modified")
                if (changes.untracked > 0) add("${changes.untracked} untracked")
                if (changes.conflicted > 0) add("${changes.conflicted} conflicted")
            }
        }
        git.status?.ahead?.takeIf { it > 0 }?.let { add("$it ahead") }
        git.status?.behind?.takeIf { it > 0 }?.let { add("$it behind") }
        git.operation?.let { add(it.label) }
    }
    return (listOf("$kind ${git.name}") + parts).joinToString(", ")
}
