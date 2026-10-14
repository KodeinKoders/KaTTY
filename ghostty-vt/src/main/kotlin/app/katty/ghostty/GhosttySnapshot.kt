package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyVt

/** A snapshot of a terminal could not be made or read. */
public class GhosttySnapshotException(message: String) : RuntimeException(message)

internal fun snapshotResult(result: Int, what: String) {
    if (result == GhosttyVt.GHOSTTY_SUCCESS()) return
    val reason = when (result) {
        GhosttyVt.GHOSTTY_INVALID_VALUE() -> "it is not a snapshot, or it is cut or damaged"
        GhosttyVt.GHOSTTY_OUT_OF_MEMORY() -> "out of memory"
        GhosttyVt.GHOSTTY_IO_ERROR() -> "it could not be read"
        GhosttyVt.GHOSTTY_LIMIT_EXCEEDED() -> "it is too large"
        else -> "error $result"
    }
    throw GhosttySnapshotException("Could not $what: $reason")
}
