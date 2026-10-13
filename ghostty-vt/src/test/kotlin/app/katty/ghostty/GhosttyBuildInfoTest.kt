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
}
