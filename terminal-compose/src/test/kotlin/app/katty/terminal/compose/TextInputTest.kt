@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextInputTest {
    private val sent = mutableListOf<String>()
    private val input = TerminalTextInput { sent += it }

    @Test
    fun aTextThatOnlyKeyTypedDeliversIsSentOnce() {
        input.keyPressed()
        input.deliver("é", TextRoute.KEY_TYPED)

        assertEquals(listOf("é"), sent)
    }

    @Test
    fun aTextThatOnlyTheInputMethodDeliversIsSentOnce() {
        input.keyPressed()
        input.deliver("é", TextRoute.INPUT_METHOD)

        assertEquals(listOf("é"), sent)
    }

    @Test
    fun aTextThatBothRoutesDeliverIsSentOnceInEitherOrder() {
        input.keyPressed()
        input.deliver("é", TextRoute.KEY_TYPED)
        input.deliver("é", TextRoute.INPUT_METHOD)
        input.keyPressed()
        input.deliver("è", TextRoute.INPUT_METHOD)
        input.deliver("è", TextRoute.KEY_TYPED)

        assertEquals(listOf("é", "è"), sent)
    }

    @Test
    fun aKeyThatRepeatsIsSentEachTimeWhicheverRouteDeliversIt() {
        repeat(3) {
            input.keyPressed()
            input.deliver("a", TextRoute.KEY_TYPED)
        }
        repeat(3) {
            input.keyPressed()
            input.deliver("b", TextRoute.INPUT_METHOD)
        }
        repeat(3) {
            input.keyPressed()
            input.deliver("c", TextRoute.KEY_TYPED)
            input.deliver("c", TextRoute.INPUT_METHOD)
        }

        assertEquals(listOf("a", "a", "a", "b", "b", "b", "c", "c", "c"), sent)
    }

    @Test
    fun twoDifferentTextsOfTheSameKeyAreBothSent() {
        input.keyPressed()
        input.deliver("é", TextRoute.KEY_TYPED)
        input.deliver("x", TextRoute.INPUT_METHOD)

        assertEquals(listOf("é", "x"), sent)
    }

    @Test
    fun theSameTextTwiceFromOneRouteIsSentTwice() {
        // Nothing says that it is a duplicate: the other route did not deliver it.
        input.keyPressed()
        input.deliver("a", TextRoute.INPUT_METHOD)
        input.deliver("a", TextRoute.INPUT_METHOD)

        assertEquals(listOf("a", "a"), sent)
    }

    @Test
    fun composesThenCommits() {
        val request = input.request { null }

        request.editText { setComposingText("'", 1) }
        assertEquals("'", input.preedit)
        assertEquals(emptyList(), sent, "What is composed is not sent")

        request.editText { commitText("é", 1) }
        assertEquals("", input.preedit)
        assertEquals(listOf("é"), sent)
    }

    @Test
    fun anEventWithCommittedAndComposedTextCommitsThenComposes() {
        // What Compose does for an input method event that has both.
        val request = input.request { null }

        request.editText {
            commitText("é", 1)
            setComposingText("'", 1)
        }

        assertEquals(listOf("é"), sent)
        assertEquals("'", input.preedit)
    }

    @Test
    fun anEmptyCompositionEndsItWithoutSendingAnything() {
        val request = input.request { null }
        request.editText { setComposingText("'", 1) }

        request.editText { setComposingText("", 1) }

        assertEquals("", input.preedit)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun finishingTheCompositionDropsItWithoutSendingIt() {
        // macOS commits what it composed with a committed text, which comes on its own.
        val request = input.request { null }
        request.editText { setComposingText("'", 1) }

        request.editText { finishComposingText() }

        assertEquals("", input.preedit)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun theRequestDescribesTheCompositionToCompose() {
        val request = input.request { null }
        assertNull(request.state.composition)

        request.editText { setComposingText("'", 1) }

        // Compose finishes a composition that the selection is not at the end of.
        assertEquals(TextRange(0, 1), request.state.composition)
        assertEquals(TextRange(1), request.state.selection)
        assertEquals("'", request.state.text)
    }

    @Test
    fun theRequestGivesTheCandidateWindowTheRectangleOfTheCursor() {
        val rect = Rect(10f, 20f, 18f, 36f)

        assertEquals(rect, input.request { rect }.focusedRectInRoot())
    }
}
