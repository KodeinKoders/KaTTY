@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(TerminalTextInput::class.java)

/** Where a text that was typed came from. */
internal enum class TextRoute {
    /** AWT's `KEY_TYPED`. */
    KEY_TYPED,

    /** The committed text of an input method event. */
    INPUT_METHOD,
}

/**
 * The text that the user types, from the two routes that AWT has for it, and the text that is being composed.
 *
 * With an input method session open, which is what makes macOS report a dead key while it is composed, the
 * final character may come as a `KEY_TYPED`, as the committed text of an input method event, or as both:
 * that depends on the platform. The text is sent once whichever it is.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class TerminalTextInput(private val onText: (String) -> Unit) {
    /** What is being composed, such as a dead key that waits for the next one. It is not sent anywhere yet. */
    var preedit: String by mutableStateOf("")
        private set

    // What each route delivered since the last key press. A text that one of them delivered and that the
    // other delivers too is one text.
    private val fromKeyTyped = ArrayList<String>()
    private val fromInputMethod = ArrayList<String>()

    /** A key goes down, or repeats: what the previous one delivered is of no concern to the next one. */
    fun keyPressed() {
        fromKeyTyped.clear()
        fromInputMethod.clear()
    }

    fun deliver(text: String, route: TextRoute) {
        val (own, other) = when (route) {
            TextRoute.KEY_TYPED -> fromKeyTyped to fromInputMethod
            TextRoute.INPUT_METHOD -> fromInputMethod to fromKeyTyped
        }
        // Each text of the other route is the duplicate of one text of this one, not of several.
        if (other.remove(text)) {
            logger.debug("{} {}: already delivered by the other route", route, text.debugString())
            return
        }
        logger.debug("{} {}", route, text.debugString())
        own += text
        onText(text)
    }

    fun compose(text: String) {
        logger.debug("composing {}", text.debugString())
        preedit = text
    }

    /** Ends the composition without sending anything: macOS commits what it composed with a committed text. */
    fun finishComposing() {
        preedit = ""
    }

    /**
     * What Compose gives the platform's input method: it makes it report the composition, and it receives the
     * text that is committed.
     *
     * @param caretRectInRoot The cursor's cell in the window, in pixels: where the candidate window goes.
     */
    fun request(caretRectInRoot: () -> Rect?): PlatformTextInputMethodRequest = object : PlatformTextInputMethodRequest {
        override val state = object : TextEditorState {
            override val text: String get() = preedit
            override val length: Int get() = preedit.length
            override fun get(index: Int): Char = preedit[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = preedit.subSequence(startIndex, endIndex)

            // Compose finishes a composition that the selection is not at the end of.
            override val selection: TextRange get() = TextRange(preedit.length)
            override val composition: TextRange? get() = if (preedit.isEmpty()) null else TextRange(0, preedit.length)
            override fun toString(): String = preedit
        }

        override val editText: (TextEditingScope.() -> Unit) -> Unit = { block -> editScope.block() }
        override val focusedRectInRoot: () -> Rect? = caretRectInRoot

        // What a text field needs, and a terminal has no use for.
        override val value: () -> TextFieldValue = { TextFieldValue(preedit) }
        override val imeOptions: ImeOptions = ImeOptions.Default
        override val onEditCommand: (List<EditCommand>) -> Unit = {}
        override val onImeAction: ((ImeAction) -> Unit)? = null
        override val textLayoutResult: () -> TextLayoutResult? = { null }
        override val textFieldRectInRoot: () -> Rect? = caretRectInRoot
        override val textClippingRectInRoot: () -> Rect? = caretRectInRoot
        override val unclippedTextOffsetInRoot: () -> Offset? = { null }
    }

    private val editScope = object : TextEditingScope {
        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            // What was composed is replaced by what is committed.
            preedit = ""
            if (text.isNotEmpty()) deliver(text.toString(), TextRoute.INPUT_METHOD)
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) = compose(text.toString())

        override fun finishComposingText() = finishComposing()

        // The terminal does not have text that the input method could edit around the cursor.
        override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) = Unit
        override fun setSelection(start: Int, end: Int) = Unit
        override fun setComposingRegion(start: Int, end: Int) = Unit
    }
}

private fun String.debugString() = "'" + map { if (it.code < 0x20) "\\u%04x".format(it.code) else it.toString() }.joinToString("") + "'"
