package app.katty.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardTextTest {
    @Test
    fun thePlainTextIsChosenWhereverItIs() {
        // A program that copies rich text sends the HTML first.
        assertEquals(1, chooseClipboardText(listOf("text/html", "text/plain")))
        assertEquals(2, chooseClipboardText(listOf("image/png", "text/rtf", "text/plain;charset=utf-8")))
        assertEquals(0, chooseClipboardText(listOf("TEXT/PLAIN")))
    }

    @Test
    fun anotherTextIsTakenWhenThereIsNoPlainOneButNotMarkup() {
        assertEquals(1, chooseClipboardText(listOf("text/html", "text/csv")))
        assertNull(chooseClipboardText(listOf("text/html", "text/uri-list")))
        assertNull(chooseClipboardText(listOf("image/png")))
        assertNull(chooseClipboardText(emptyList()))
    }
}
