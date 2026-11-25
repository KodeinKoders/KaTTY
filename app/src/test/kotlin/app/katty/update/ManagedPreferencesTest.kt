package app.katty.update

import app.katty.core.ManagedUpdates
import app.katty.core.UpdateAction
import app.katty.core.UpdateCheck
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ManagedPreferencesTest {
    // A domain of the test's own, never the application's.
    private val domain = "net.kodein.app.katty.test-${ProcessHandle.current().pid()}"

    @AfterTest
    fun removeTheDomain() {
        ProcessBuilder("defaults", "delete", domain).redirectErrorStream(true).start().waitFor()
    }

    @Test
    fun onlyWhatIsImposedCounts() {
        val preferences = mapOf(
            "UpdatesDisabled" to Preference(true, forced = true),
            "UpdateCheck" to Preference("weekly", forced = true),
            "UpdateAction" to Preference("notify", forced = false),
            "UpdateFeedURL" to Preference("https://mirror.example.com/latest.json", forced = true),
        )
        assertEquals(
            ManagedUpdates(disabled = true, check = UpdateCheck.WEEKLY, action = null, feed = "https://mirror.example.com/latest.json"),
            managedUpdates { preferences[it] },
        )
    }

    @Test
    fun aValueOfAnotherTypeOrNameIsNone() {
        val preferences = mapOf(
            "UpdatesDisabled" to Preference("yes", forced = true),
            "UpdateCheck" to Preference("monthly", forced = true),
            "UpdateAction" to Preference("install-on-quit", forced = true),
            "UpdateFeedURL" to Preference(" ", forced = true),
        )
        assertEquals(ManagedUpdates(action = UpdateAction.INSTALL_ON_QUIT), managedUpdates { preferences[it] })
    }

    @Test
    fun aPreferenceThatTheUserWroteIsReadAndIsNotImposed() {
        ProcessBuilder("defaults", "write", domain, "UpdatesDisabled", "-bool", "true").start().waitFor()
        ProcessBuilder("defaults", "write", domain, "UpdateCheck", "-string", "weekly").start().waitFor()
        assertEquals(Preference(true, forced = false), readPreference(domain, "UpdatesDisabled"))
        assertEquals(Preference("weekly", forced = false), readPreference(domain, "UpdateCheck"))
        assertNull(readPreference(domain, "UpdateAction"))
        assertFalse(managedUpdates { readPreference(domain, it) }.disabled == true)
    }
}
