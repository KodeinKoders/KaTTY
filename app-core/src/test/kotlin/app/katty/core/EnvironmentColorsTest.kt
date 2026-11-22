package app.katty.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentColorsTest {
    @Test
    fun aTintIsKeptByItsName() {
        val orange = EnvironmentColor.Tint(BadgeTint.ORANGE)

        assertEquals("orange", orange.encoded)
        assertEquals("Orange", orange.label)
        assertEquals(orange, EnvironmentColor.decode("orange"))
    }

    @Test
    fun aHexIsKeptInUpperCaseWithItsHash() {
        val hex = EnvironmentColor.Hex(0xE08A00)

        assertEquals("#E08A00", hex.encoded)
        assertEquals("#E08A00", hex.label)
        assertEquals(hex, EnvironmentColor.decode("#E08A00"))
    }

    @Test
    fun whatIsNeitherATintNorAHexIsNoColor() {
        assertNull(EnvironmentColor.decode("crimson"))
        assertNull(EnvironmentColor.decode(""))
        assertNull(EnvironmentColor.decode("#E08"))
    }

    @Test
    fun aTypedHexMayHaveNoHashAndAnyCase() {
        assertEquals(EnvironmentColor.Hex(0xE08A00), EnvironmentColor.parseHex("e08a00"))
        assertEquals(EnvironmentColor.Hex(0xE08A00), EnvironmentColor.parseHex("#E08A00"))
        assertEquals(EnvironmentColor.Hex(0xE08A00), EnvironmentColor.parseHex(" #e08A00 "))
        assertNull(EnvironmentColor.parseHex("#e08"))
        assertNull(EnvironmentColor.parseHex("zzzzzz"))
        assertNull(EnvironmentColor.parseHex("##e08a00"))
        assertNull(EnvironmentColor.parseHex("+e08a0"))
    }

    @Test
    fun anEnvironmentWithNoColorHasTheOneItsNameFallsOn() {
        val settings = Settings().withEnvironmentColor("kodein", EnvironmentColor.Tint(BadgeTint.GREEN))

        assertEquals(EnvironmentColor.Tint(BadgeTint.GREEN), settings.environmentColor("kodein"))
        assertNull(settings.environmentColor("perso"))
        assertNull(settings.withEnvironmentColor("kodein", null).environmentColor("kodein"))
        assertEquals(emptyMap(), settings.withEnvironmentColor("kodein", null).environmentColors)
    }

    @Test
    fun theColorsAreKeptInTheFile() {
        val directory = Files.createTempDirectory("katty-environment-colors")
        try {
            val path = directory.resolve("state.json")
            val settings = Settings()
                .withEnvironmentColor("kodein", EnvironmentColor.Tint(BadgeTint.ORANGE))
                .withEnvironmentColor("gone", EnvironmentColor.Hex(0x00AA55))

            StateFile(path).save(KattyState(settings = settings))
            val loaded = StateFile(path).load().settings

            assertEquals(mapOf("kodein" to "orange", "gone" to "#00AA55"), loaded.environmentColors)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
