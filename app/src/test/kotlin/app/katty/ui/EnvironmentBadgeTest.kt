package app.katty.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.katty.core.BadgeTint
import app.katty.core.EnvironmentBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentBadgeTest {
    @Test
    fun anEnvironmentWithNoColorHasTheTintItsNameFallsOn() {
        val tint = hashedTint("kodein")

        assertEquals(KattyColors.Light.badge(tint), environmentColor("kodein", KattyColors.Light))
        assertEquals(KattyColors.Dark.badge(tint), environmentColor("kodein", KattyColors.Dark))
        assertEquals(tint, hashedTint("kodein"), "The same name falls on the same tint")
    }

    @Test
    fun aTintFollowsTheTheme() {
        val chosen = mapOf("kodein" to "orange")

        assertEquals(KattyColors.Light.badge(BadgeTint.ORANGE), environmentColor("kodein", KattyColors.Light, chosen))
        assertEquals(KattyColors.Dark.badge(BadgeTint.ORANGE), environmentColor("kodein", KattyColors.Dark, chosen))
    }

    @Test
    fun aHexIsTheSameInBothThemes() {
        val chosen = mapOf("kodein" to "#E08A00")

        assertEquals(Color(0xFFE08A00), environmentColor("kodein", KattyColors.Light, chosen))
        assertEquals(Color(0xFFE08A00), environmentColor("kodein", KattyColors.Dark, chosen))
    }

    @Test
    fun aColorThatCannotBeReadIsTheTintTheNameFallsOn() {
        assertEquals(environmentColor("kodein", KattyColors.Light), environmentColor("kodein", KattyColors.Light, mapOf("kodein" to "crimson")))
    }

    @Test
    fun theBadgesOfTheWindowsHaveTheColorOfTheSettings() {
        val rendered = render("environment-pill-chosen", 200, 40) {
            CompositionLocalProvider(LocalEnvironmentColors provides mapOf("kodein" to "#E08A00")) { EnvironmentPill(EnvironmentBadge("kodein")) }
        }

        assertTrue(rendered.count(0xE08A00) > 0, "The text is in the chosen color")
    }

    @Test
    fun anEnvironmentThatHasNoFileIsRedWhateverItsColor() {
        val rendered = render("environment-pill-missing", 200, 40) {
            CompositionLocalProvider(LocalEnvironmentColors provides mapOf("kodein" to "#E08A00")) { EnvironmentPill(EnvironmentBadge("kodein", missing = true)) }
        }

        assertEquals(0, rendered.count(0xE08A00))
        assertTrue(rendered.count(KattyColors.Light.err.toArgb() and 0xFFFFFF) > 0, "The text is red")
    }
}
