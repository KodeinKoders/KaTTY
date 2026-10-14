package app.katty.terminal.compose

import androidx.compose.ui.input.pointer.PointerIcon
import app.katty.terminal.TerminalLink
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalPointerShape
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksUiTest {
    private val cellWidth = 10f
    private val cellHeight = 20f

    private val first = TerminalLink("https://one.example", 0, 2..5)
    private val second = TerminalLink("https://two.example", 1, 0..9)

    private val linkAt: (Int, Int) -> TerminalLink? = { column, row ->
        when {
            row == 0 && column in first.columns -> first
            row == 1 && column in second.columns -> second
            else -> null
        }
    }

    private val opened = mutableListOf<String>()

    // A pointer event in the cell at [column] and [row], with the modifier that opens links or without.
    private fun LinkInteraction.event(
        kind: PointerInfo.Kind, column: Int, row: Int, modifier: Boolean = true, button: TerminalMouseButton? = null, mac: Boolean = true,
    ) = handle(
        PointerInfo(kind, (column + 0.5f) * cellWidth, (row + 0.5f) * cellHeight, button = button, meta = modifier && mac, ctrl = modifier && !mac),
        cellWidth, cellHeight, linkAt, { opened += it }, mac,
    )

    @Test
    fun commandOnMacOSAndControlElsewhereAreTheModifier() {
        assertTrue(linkModifier(shift = false, ctrl = false, alt = false, meta = true, mac = true))
        assertTrue(linkModifier(shift = false, ctrl = true, alt = false, meta = false, mac = false))
        assertFalse(linkModifier(shift = false, ctrl = true, alt = false, meta = false, mac = true))
        assertFalse(linkModifier(shift = false, ctrl = false, alt = false, meta = true, mac = false))
    }

    @Test
    fun anotherModifierHeldMakesItAnotherGesture() {
        // Option and Command is a block selection, and Shift selects.
        assertFalse(linkModifier(shift = false, ctrl = false, alt = true, meta = true, mac = true))
        assertFalse(linkModifier(shift = true, ctrl = false, alt = false, meta = true, mac = true))
        assertFalse(linkModifier(shift = true, ctrl = true, alt = false, meta = false, mac = false))
        assertFalse(linkModifier(shift = false, ctrl = false, alt = false, meta = false, mac = true))
    }

    @Test
    fun theLinkUnderThePointerIsHoveredWhileTheModifierIsHeld() {
        val links = LinkInteraction()

        links.event(PointerInfo.Kind.MOVE, 3, 0)
        assertEquals(first, links.hovered)

        links.event(PointerInfo.Kind.MOVE, 7, 0)
        assertNull(links.hovered, "Past the link")

        links.event(PointerInfo.Kind.MOVE, 3, 0)
        links.event(PointerInfo.Kind.MOVE, 3, 0, modifier = false)
        assertNull(links.hovered, "The modifier was released")
    }

    @Test
    fun noLinkIsHoveredWithoutTheModifier() {
        val links = LinkInteraction()

        links.event(PointerInfo.Kind.MOVE, 3, 0, modifier = false)

        assertNull(links.hovered)
    }

    @Test
    fun pressingAndReleasingOnALinkOpensItAndTheProgramIsTold_Nothing() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0)

        assertTrue(links.event(PointerInfo.Kind.PRESS, 3, 0, button = TerminalMouseButton.LEFT), "The press is the link's")
        assertTrue(links.event(PointerInfo.Kind.MOVE, 4, 0), "And the drag")
        assertEquals(emptyList(), opened, "Nothing is opened until the release")
        assertTrue(links.event(PointerInfo.Kind.RELEASE, 4, 0, button = TerminalMouseButton.LEFT), "And the release")

        assertEquals(listOf("https://one.example"), opened)
    }

    @Test
    fun aReleaseAwayFromTheLinkOpensNothing() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0)
        links.event(PointerInfo.Kind.PRESS, 3, 0, button = TerminalMouseButton.LEFT)

        assertTrue(links.event(PointerInfo.Kind.RELEASE, 8, 0, button = TerminalMouseButton.LEFT))
        links.event(PointerInfo.Kind.RELEASE, 8, 0, button = TerminalMouseButton.LEFT)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun aReleaseOnAnotherLinkOpensNothing() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0)
        links.event(PointerInfo.Kind.PRESS, 3, 0, button = TerminalMouseButton.LEFT)

        links.event(PointerInfo.Kind.RELEASE, 3, 1, button = TerminalMouseButton.LEFT)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun aClickThatIsNotOnALinkIsNotTheLinks() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 8, 0)

        assertFalse(links.event(PointerInfo.Kind.PRESS, 8, 0, button = TerminalMouseButton.LEFT))
        assertFalse(links.event(PointerInfo.Kind.RELEASE, 8, 0, button = TerminalMouseButton.LEFT))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun aClickWithoutTheModifierIsNotTheLinksEither() {
        val links = LinkInteraction()

        assertFalse(links.event(PointerInfo.Kind.PRESS, 3, 0, modifier = false, button = TerminalMouseButton.LEFT))
        assertFalse(links.event(PointerInfo.Kind.RELEASE, 3, 0, modifier = false, button = TerminalMouseButton.LEFT))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun anotherButtonIsNotTheLinks() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0)

        assertFalse(links.event(PointerInfo.Kind.PRESS, 3, 0, button = TerminalMouseButton.RIGHT))
        assertFalse(links.event(PointerInfo.Kind.PRESS, 3, 0, button = TerminalMouseButton.MIDDLE))
    }

    @Test
    fun theLinkGoesWhenThePointerLeaves() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0)

        links.exit()

        assertNull(links.hovered)
    }

    @Test
    fun theLinkShowsWhenTheModifierIsPressedWithoutTheMouseMoving() {
        val links = LinkInteraction()
        links.event(PointerInfo.Kind.MOVE, 3, 0, modifier = false)
        assertNull(links.hovered)

        links.refresh(true, cellWidth, cellHeight, linkAt)
        assertEquals(first, links.hovered)

        links.refresh(false, cellWidth, cellHeight, linkAt)
        assertNull(links.hovered)
    }

    @Test
    fun theHandIsThePointerOverALink() {
        assertEquals(
            PointerIcon.Hand,
            terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, SelectionModifiers.NONE, overLink = true),
        )
        assertEquals(
            PointerIcon.Default,
            terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, SelectionModifiers.NONE, overLink = false),
        )
    }

    @Test
    fun theKeysThatJumpBetweenPromptsAreCommandOnMacOSAndControlAndShiftElsewhere() {
        fun key(code: Int, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, kind: KeyInfo.Kind = KeyInfo.Kind.PRESSED) =
            KeyInfo(kind, code, AwtKeyEvent.KEY_LOCATION_STANDARD, AwtKeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta)

        assertEquals(-1, promptJump(key(AwtKeyEvent.VK_UP, meta = true), mac = true))
        assertEquals(1, promptJump(key(AwtKeyEvent.VK_DOWN, meta = true), mac = true))
        assertEquals(-1, promptJump(key(AwtKeyEvent.VK_UP, ctrl = true, shift = true), mac = false))
        assertEquals(1, promptJump(key(AwtKeyEvent.VK_DOWN, meta = true, kind = KeyInfo.Kind.RELEASED), mac = true), "Its release is consumed too")

        assertNull(promptJump(key(AwtKeyEvent.VK_UP), mac = true))
        assertNull(promptJump(key(AwtKeyEvent.VK_UP, meta = true, shift = true), mac = true))
        assertNull(promptJump(key(AwtKeyEvent.VK_UP, meta = true, alt = true), mac = true))
        assertNull(promptJump(key(AwtKeyEvent.VK_UP, ctrl = true), mac = false))
        assertNull(promptJump(key(AwtKeyEvent.VK_UP, meta = true), mac = false))
        assertNull(promptJump(key(AwtKeyEvent.VK_LEFT, meta = true), mac = true))
    }
}
