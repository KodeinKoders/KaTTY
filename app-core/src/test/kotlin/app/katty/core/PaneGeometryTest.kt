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

class SplitNudgeTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val layout get() = store.state.value.selectedWorkspace!!.layout

    // Left | (top / bottom): a split in a row, and in its second side a split in a column.
    private fun built(): Triple<TerminalId, TerminalId, TerminalId> {
        store.addProject("/a")
        val left = store.state.value.selection.terminal!!
        val top = store.split(left, SplitDirection.ROW)!!
        val bottom = store.split(top, SplitDirection.COLUMN)!!
        return Triple(left, top, bottom)
    }

    private fun rootRatio() = (layout as PaneNode.Split).ratio

    @Test
    fun rightAndDownGiveTheFirstSideMoreAndLeftAndUpLess() {
        val (left, top, _) = built()
        val root = (layout as PaneNode.Split).id

        assertEquals(root to 0.55f, layout.splitToNudge(left, Direction.RIGHT, 0.05f))
        assertEquals(root to 0.45f, layout.splitToNudge(left, Direction.LEFT, 0.05f))
        // The pane that is on the right of the divider moves the same divider the same way: the arrow says where the divider goes.
        assertEquals(root to 0.55f, layout.splitToNudge(top, Direction.RIGHT, 0.05f))
    }

    @Test
    fun theNearestSplitAlongTheArrowIsTheOneThatMoves() {
        val (_, top, bottom) = built()
        val inner = ((layout as PaneNode.Split).second as PaneNode.Split).id

        assertEquals(inner to 0.55f, layout.splitToNudge(top, Direction.DOWN, 0.05f))
        assertEquals(inner to 0.45f, layout.splitToNudge(bottom, Direction.UP, 0.05f))
        // The top and bottom panes are in a split in a row too, so the left and right arrows move that one.
        assertEquals((layout as PaneNode.Split).id, layout.splitToNudge(bottom, Direction.LEFT, 0.05f)!!.first)
    }

    @Test
    fun noSplitAlongTheArrowMeansNothingMoves() {
        val (left, _, _) = built()

        assertNull(layout.splitToNudge(left, Direction.DOWN, 0.05f), "The left pane is only in a split in a row")
        assertNull(layout.splitToNudge(TerminalId("gone"), Direction.RIGHT, 0.05f))
    }

    @Test
    fun theVerticalLayoutDrawsEverySplitTurned() {
        val (left, top, _) = built()
        val root = (layout as PaneNode.Split).id
        val inner = ((layout as PaneNode.Split).second as PaneNode.Split).id

        // The root is a row in the model and a column on the screen: the up and down arrows move it, and the left and right ones the inner split.
        assertEquals(root to 0.55f, layout.splitToNudge(left, Direction.DOWN, 0.05f, flipped = true))
        assertNull(layout.splitToNudge(left, Direction.RIGHT, 0.05f, flipped = true))
        assertEquals(inner to 0.45f, layout.splitToNudge(top, Direction.LEFT, 0.05f, flipped = true))
    }

    @Test
    fun theRatioStaysBetweenTheLimits() {
        val (left, _, _) = built()
        val root = (layout as PaneNode.Split).id
        store.setSplitRatio(store.state.value.selection.workspace!!, root, 0.93f)

        assertEquals(root to 0.95f, layout.splitToNudge(left, Direction.RIGHT, 0.05f))
        store.setSplitRatio(store.state.value.selection.workspace!!, root, 0.07f)
        assertEquals(root to 0.05f, layout.splitToNudge(left, Direction.LEFT, 0.05f))
    }
}
