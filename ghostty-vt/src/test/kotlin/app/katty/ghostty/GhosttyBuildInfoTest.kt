package app.katty.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhosttyBuildInfoTest {
    @Test
    fun reportsTheBundledBuild() {
        val info = GhosttyBuildInfo.current()

        assertEquals("0.1.0-dev", info.version)
        assertTrue(info.kittyGraphics, "The Kitty graphics protocol is built in")
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resource(name: String) =
        GhosttyBuildInfoTest::class.java.getResourceAsStream(NativeLibrary.resources().first + name)!!.use { it.readBytes() }

    @Test
    fun theFingerprintIsOfWhatTheBuildSaysAndNotOfTheBytesOfTheLibrary() {
        val stamp = resource("libghostty-vt.stamp").toString(Charsets.UTF_8)

        // The commit of the submodule, or a hash of its sources when it was built from a copy without git.
        assertTrue(Regex("(ghostty=[0-9a-f]{40}|ghostty-sources=[0-9a-f]{64})\n").containsMatchIn(stamp), "What Ghostty was built from: $stamp")
        assertTrue(Regex("zig=\\d+\\.\\d+\\.\\d+\n").containsMatchIn(stamp), "The Zig that built it: $stamp")
        assertTrue("-Demit-lib-vt" in stamp, "What it was built with: $stamp")
        assertEquals(sha256(stamp.toByteArray()), GhosttyBuildInfo.fingerprint())
        // Two builds of the same commit are not the same file, and must have the same fingerprint.
        assertTrue(GhosttyBuildInfo.fingerprint() != sha256(resource(NativeLibrary.resources().second)))
    }
}
