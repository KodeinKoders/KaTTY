package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutsTest {
    @Test
    fun autoIsVerticalOnlyWhenTheWindowIsTallerThanItIsWide() {
        assertEquals(Orientation.HORIZONTAL, LayoutMode.AUTO.orientation(1440f, 900f))
        assertEquals(Orientation.VERTICAL, LayoutMode.AUTO.orientation(810f, 1440f))
        assertEquals(Orientation.HORIZONTAL, LayoutMode.AUTO.orientation(900f, 900f))
    }

    @Test
    fun aChosenLayoutDoesNotDependOnTheShapeOfTheWindow() {
        assertEquals(Orientation.HORIZONTAL, LayoutMode.HORIZONTAL.orientation(810f, 1440f))
        assertEquals(Orientation.VERTICAL, LayoutMode.VERTICAL.orientation(1440f, 900f))
    }

    @Test
    fun theInitialsAreTheOnesOfTheFirstTwoWords() {
        assertEquals("AG", initialsOf("api-gateway"))
        assertEquals("DS", initialsOf("docs-site"))
        assertEquals("IT", initialsOf("infra-terraform"))
        assertEquals("WC", initialsOf("web client"))
        assertEquals("AB", initialsOf("a_b_c"))
    }

    @Test
    fun aNameOfOneWordHasItsFirstTwoLetters() {
        assertEquals("KA", initialsOf("katty"))
        assertEquals("X", initialsOf("x"))
    }

    @Test
    fun aNameWithNoLetterHasAQuestionMark() {
        assertEquals("?", initialsOf("--"))
        assertEquals("?", initialsOf(""))
    }
}
