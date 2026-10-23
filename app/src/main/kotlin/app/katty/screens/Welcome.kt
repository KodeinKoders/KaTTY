package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.AppShortcut
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.Shortcut
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

/**
 * What the window shows when there is no project: it says what a project is, and asks for a folder. The agents that are on the machine
 * are listed under it by [detected].
 */
@Composable
fun Welcome(onAddProject: () -> Unit, modifier: Modifier = Modifier, detected: @Composable () -> Unit = {}) {
    val colors = Katty.colors
    Box(modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(KattyIcons.FolderPlus, colors.accent, size = 48.dp)
            Text("Add your first project", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            Text(
                "A project is a folder on your disk. Every terminal you open inside it starts in that folder, and you can group them into workspaces.",
                color = colors.muted, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DefaultButton(onClick = onAddProject) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(KattyIcons.FolderPlus, androidx.compose.ui.graphics.Color.White, size = 16.dp)
                        Text("Add project folder…")
                    }
                }
                // The keys that the user bound it to, and none when it is bound to none.
                AppShortcut.ADD_PROJECT.label.takeIf { it.isNotEmpty() }?.let { Shortcut(shortcutKeys(it)) }
            }
            detected()
        }
    }
}
