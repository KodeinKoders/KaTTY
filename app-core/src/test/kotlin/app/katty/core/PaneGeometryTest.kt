package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaneGeometryTest {
    private fun leaf(id: String) = PaneNode.Leaf(TerminalSpec(TerminalId(id), directory = "/"))
    private var splits = 0
    private fun split(direction: SplitDirection, first: PaneNode, second: PaneNode, ratio: Float = 0.5f) =
        PaneNode.Split(SplitId("s${splits++}"), direction, ratio, first, second)

    // A beside (B above C): the tree of the main screen of the design.
    private val tree = split(SplitDirection.ROW, leaf("A"), split(SplitDirection.COLUMN, leaf("B"), leaf("C")))

    private fun PaneNode.go(from: String, direction: Direction, flipped: Boolean = false) = neighbour(TerminalId(from), direction, flipped)?.value

    @Test
    fun theBoxesFollowTheRatios() {
        val boxes = split(SplitDirection.ROW, leaf("A"), leaf("B"), ratio = 0.25f).boxes()

        assertEquals(PaneBox(0.0, 0.0, 0.25, 1.0), boxes[TerminalId("A")])
        assertEquals(PaneBox(0.25, 0.0, 1.0, 1.0), boxes[TerminalId("B")])
    }

    @Test
    fun aFlippedTreeIsDrawnWithEverySplitTurned() {
        val boxes = tree.boxes(flipped = true)

        // A over (B beside C).
        assertEquals(PaneBox(0.0, 0.0, 1.0, 0.5), boxes[TerminalId("A")])
        assertEquals(PaneBox(0.0, 0.5, 0.5, 1.0), boxes[TerminalId("B")])
        assertEquals(PaneBox(0.5, 0.5, 1.0, 1.0), boxes[TerminalId("C")])
    }

    @Test
    fun goesToTheNeighbourInEachDirection() {
        assertEquals("B", tree.go("A", Direction.RIGHT))
        assertEquals("A", tree.go("B", Direction.LEFT))
        assertEquals("A", tree.go("C", Direction.LEFT))
        assertEquals("C", tree.go("B", Direction.DOWN))
        assertEquals("B", tree.go("C", Direction.UP))
    }

    @Test
    fun thereIsNoNeighbourPastTheEdge() {
        assertNull(tree.go("A", Direction.LEFT))
        assertNull(tree.go("A", Direction.UP))
        assertNull(tree.go("A", Direction.DOWN))
        assertNull(tree.go("B", Direction.UP))
        assertNull(tree.go("C", Direction.DOWN))
        assertNull(tree.go("B", Direction.RIGHT))
    }

    @Test
    fun theNeighbourThatSharesTheMostIsChosen() {
        // A is as tall as B and C together, B is the larger of the two: from A to the right is B.
        val uneven = split(SplitDirection.ROW, leaf("A"), split(SplitDirection.COLUMN, leaf("B"), leaf("C"), ratio = 0.7f))

        assertEquals("B", uneven.go("A", Direction.RIGHT))
        assertEquals("C", split(SplitDirection.ROW, leaf("A"), split(SplitDirection.COLUMN, leaf("B"), leaf("C"), ratio = 0.3f)).go("A", Direction.RIGHT))
    }

    @Test
    fun theScreenDecidesWhichWayIsWhichWhenTheTreeIsFlipped() {
        // A over (B beside C): going down from A reaches B (the larger share ties, the left one is first), and right from B reaches C.
        assertEquals("B", tree.go("A", Direction.DOWN, flipped = true))
        assertEquals("C", tree.go("B", Direction.RIGHT, flipped = true))
        assertEquals("A", tree.go("C", Direction.UP, flipped = true))
        assertNull(tree.go("A", Direction.RIGHT, flipped = true))
    }

    @Test
    fun aTerminalThatIsNotThereHasNoNeighbour() {
        assertNull(tree.go("nothing", Direction.RIGHT))
    }

    @Test
    fun aSingleTerminalHasNoNeighbour() {
        Direction.entries.forEach { assertNull(leaf("A").go("A", it)) }
    }
}
