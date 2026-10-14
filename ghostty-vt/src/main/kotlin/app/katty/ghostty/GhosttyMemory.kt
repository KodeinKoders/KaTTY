package app.katty.ghostty

/** How much a terminal holds in native memory, adding up its two screens. */
public data class GhosttyMemoryUsage(
    /** Whether compressing the scrollback frees memory on this platform. */
    val compressionSupported: Boolean,
    /** The physical memory that the pages of the screens use now: what to budget against. A compressed page counts its compressed size. */
    val residentBytes: Long,
    /** The address space reserved for the pages, which compressing does not lower: each page keeps its space to be decompressed in. */
    val virtualBytes: Long,
    val compressedPages: Long,
    /** What the compressed pages take, which is part of [residentBytes]. */
    val compressedBytes: Long,
    /** The images of the Kitty graphics protocol, which are not part of [residentBytes]. */
    val imageBytes: Long,
)

/** How much of the scrollback to compress in one call. */
public enum class GhosttyCompressionMode {
    /** A bounded amount of work, for an idle moment: [GhosttyCompressionResult.PENDING] asks for another call. */
    INCREMENTAL,

    /** All of it in one go, which can take a while for a large scrollback. */
    FULL,
}

public enum class GhosttyCompressionResult {
    /** Compressing does not free memory on this platform. */
    UNSUPPORTED,

    /** There is more to do: call again while the terminal is idle. */
    PENDING,

    /** The pass is finished, which does not mean that every page was compressed. Nothing more to do until the activity changes. */
    COMPLETE,
}
