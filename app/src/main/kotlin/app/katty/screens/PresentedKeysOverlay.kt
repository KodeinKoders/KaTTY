package app.katty.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.PresentedKeys
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text

// The same in both themes: it is drawn over terminals of any color scheme, and a stream shows it as a keystroke overlay always looks.
private val PILL = Color(0xE61B1C20)
private val KEYS = Color(0xFFFFFFFF)
private val CAPTION = Color(0xB3FFFFFF)
// An edge, so that it stands out on a dark terminal too.
private val EDGE = Color(0x33FFFFFF)

// How long a line takes to fade out, at the end of the time it is shown.
private const val FADE_MS = 400

/**
 * The keys that presentation mode shows ([keys]), one line each, the newest at the bottom: the keys in big letters and what they did. A line fades
 * out at the end of its time, and is taken off then. It takes no pointer events: what is under it is clicked.
 */
@Composable
internal fun PresentedKeysOverlay(keys: PresentedKeys, modifier: Modifier = Modifier) {
    val lines = keys.lines
    // Takes off the line whose time is over first, then the next: the lines changing starts it again.
    LaunchedEffect(lines) {
        val first = lines.minOfOrNull { it.at } ?: return@LaunchedEffect
        delay((first + PresentedKeys.SHOWN_MS - System.currentTimeMillis()).coerceAtLeast(0))
        keys.expire()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in lines) {
            key(line.id) {
                // Shown whole while it is pressed, faded at the end of its time.
                val alpha = remember { Animatable(1f) }
                LaunchedEffect(line.at) {
                    alpha.snapTo(1f)
                    delay((line.at + PresentedKeys.SHOWN_MS - FADE_MS - System.currentTimeMillis()).coerceAtLeast(0))
                    alpha.animateTo(0f, tween(FADE_MS))
                }
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.graphicsLayer { this.alpha = alpha.value }.clip(shape).background(PILL).border(1.dp, EDGE, shape)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(line.text, color = KEYS, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    line.key.caption?.let { Text(it, color = CAPTION, fontSize = 16.sp) }
                }
            }
        }
    }
}
