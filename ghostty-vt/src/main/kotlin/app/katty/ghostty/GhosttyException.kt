package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyVt

/**
 * A libghostty-vt call returned an error.
 */
public class GhosttyException(
    public val result: Int,
    message: String,
) : RuntimeException(message)

internal fun checkResult(result: Int, operation: String) {
    if (result == GhosttyVt.GHOSTTY_SUCCESS()) return
    val reason = when (result) {
        GhosttyVt.GHOSTTY_OUT_OF_MEMORY() -> "out of memory"
        GhosttyVt.GHOSTTY_INVALID_VALUE() -> "invalid value"
        GhosttyVt.GHOSTTY_OUT_OF_SPACE() -> "out of space"
        GhosttyVt.GHOSTTY_NO_VALUE() -> "no value"
        else -> "error $result"
    }
    throw GhosttyException(result, "$operation failed: $reason")
}

internal fun Int.toUInt16(what: String): Short {
    require(this in 1..0xFFFF) { "$what must be in 1..65535, was $this" }
    return toShort()
}
