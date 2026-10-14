@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opens an input method session while [enabled], so that the platform reports what is being composed and
 * the text that is committed to [input].
 *
 * [enabled] is whether the composable has the focus of Compose, **not** the one of the window: opening or
 * closing a session moves the focus of AWT for a moment, which would close and open it again if it
 * followed the window's focus.
 *
 * [caretRectInRoot] is called when asked: it has to be the same lambda from one composition to the next, or
 * the session is restarted every time.
 */
internal fun Modifier.terminalTextInput(
    input: TerminalTextInput,
    enabled: Boolean,
    caretRectInRoot: () -> Rect?,
): Modifier = this then TextInputElement(input, enabled, caretRectInRoot)

private data class TextInputElement(
    val input: TerminalTextInput,
    val enabled: Boolean,
    val caretRectInRoot: () -> Rect?,
) : ModifierNodeElement<TextInputNode>() {
    override fun create() = TextInputNode(input, enabled, caretRectInRoot)

    override fun update(node: TextInputNode) = node.update(input, enabled, caretRectInRoot)

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalTextInput"
        properties["enabled"] = enabled
    }
}

private class TextInputNode(
    private var input: TerminalTextInput,
    private var enabled: Boolean,
    private var caretRectInRoot: () -> Rect?,
) : Modifier.Node(), PlatformTextInputModifierNode {
    private var session: Job? = null

    fun update(input: TerminalTextInput, enabled: Boolean, caretRectInRoot: () -> Rect?) {
        val restart = this.input !== input || this.enabled != enabled
        this.input = input
        this.enabled = enabled
        this.caretRectInRoot = caretRectInRoot
        if (restart) start()
    }

    override fun onAttach() = start()

    override fun onDetach() = stop()

    private fun start() {
        stop()
        if (!enabled || !isAttached) return
        val input = input
        val caretRectInRoot = caretRectInRoot
        session = coroutineScope.launch {
            establishTextInputSession {
                startInputMethod(input.request { caretRectInRoot() })
            }
        }
    }

    private fun stop() {
        session?.cancel()
        session = null
        // What was being composed is not going to be finished.
        input.finishComposing()
    }
}
