package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.MemorySegment

/**
 * A place in the terminal that follows its row as the terminal scrolls, reflows and prunes its history, until it goes with the row
 * ([hasValue] is then `false`).
 *
 * It belongs to the terminal that made it, which it must not outlive, and, like everything of a terminal, [close] and the calls that
 * use it must not be concurrent with other calls on that terminal: the caller serializes them with the same lock.
 */
public class GhosttyTrackedRef internal constructor(internal val handle: MemorySegment) : AutoCloseable {
    private var closed = false

    /** Whether the row is still in the history or the screen. */
    public val hasValue: Boolean
        get() = !closed && GhosttyVt.ghostty_tracked_grid_ref_has_value(handle)

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_tracked_grid_ref_free(handle)
    }
}
