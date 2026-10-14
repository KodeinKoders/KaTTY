package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

public enum class GhosttyOptimizeMode {
    DEBUG,
    RELEASE_SAFE,
    RELEASE_SMALL,
    RELEASE_FAST,
}

/**
 * How the loaded libghostty-vt was built.
 */
public data class GhosttyBuildInfo(
    /** For example `0.1.0-dev`. The library has no releases: this does not identify its commit. */
    public val version: String,
    public val optimizeMode: GhosttyOptimizeMode,
    public val simd: Boolean,
    public val kittyGraphics: Boolean,
    public val tmuxControlMode: Boolean,
) {
    public companion object {
        /**
         * Which library this is: a hash of the file that was loaded. The library does not say which commit it was built from, and the
         * snapshots that it makes are only good for the same build.
         */
        public fun fingerprint(): String {
            NativeLibrary.ensureLoaded()
            return NativeLibrary.fingerprint
        }

        public fun current(): GhosttyBuildInfo {
            NativeLibrary.ensureLoaded()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(GhosttyString.layout())

                fun query(info: Int, out: MemorySegment) =
                    checkResult(GhosttyVt.ghostty_build_info(info, out), "ghostty_build_info")

                fun flag(info: Int): Boolean {
                    query(info, out)
                    return out.get(ValueLayout.JAVA_BOOLEAN, 0)
                }

                query(GhosttyVt.GHOSTTY_BUILD_INFO_OPTIMIZE(), out)
                val optimize = GhosttyOptimizeMode.entries[out.get(ValueLayout.JAVA_INT, 0)]

                query(GhosttyVt.GHOSTTY_BUILD_INFO_VERSION_STRING(), out)
                val version = GhosttyString.ptr(out)
                    .reinterpret(GhosttyString.len(out))
                    .toArray(ValueLayout.JAVA_BYTE)
                    .decodeToString()

                GhosttyBuildInfo(
                    version = version,
                    optimizeMode = optimize,
                    simd = flag(GhosttyVt.GHOSTTY_BUILD_INFO_SIMD()),
                    kittyGraphics = flag(GhosttyVt.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS()),
                    tmuxControlMode = flag(GhosttyVt.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE()),
                )
            }
        }
    }
}
