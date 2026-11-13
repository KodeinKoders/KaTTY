package app.katty

import app.katty.core.BoardColumn
import app.katty.core.column
import app.katty.terminals.AgentTransition

/** The least time between two sounds: agents that start needing the user together, as the ones that come back at launch do, make one. */
private const val ATTENTION_SOUND_INTERVAL_NS = 1_000_000_000L

/**
 * The sound of an agent that starts needing the user: the alert sound of the system, the one that the user chose and at the volume they gave to
 * alerts, which is also what the bell of a terminal plays.
 *
 * It is only called from the window, on its thread.
 *
 * @param clock The time in nanoseconds, from any origin.
 * @param sound What makes the sound.
 */
class AttentionSound(
    private val clock: () -> Long = System::nanoTime,
    private val sound: () -> Unit = { java.awt.Toolkit.getDefaultToolkit().beep() },
) {
    // When the last sound was made, `null` before the first.
    private var last: Long? = null

    /** Makes the sound, unless one was made less than a second ago. */
    fun play() {
        val now = clock()
        last?.let { if (now - it < ATTENTION_SOUND_INTERVAL_NS) return }
        last = now
        sound()
    }
}

/**
 * Whether the terminal starts needing the user: its agent waits for them or failed, and did neither before. A wait that becomes a failure
 * needed them already.
 */
internal fun AgentTransition.startsNeedingTheUser(): Boolean =
    info.state.column() == BoardColumn.NEEDS_INPUT && from.column() != BoardColumn.NEEDS_INPUT
