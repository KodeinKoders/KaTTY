package app.katty.core

import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private val sha = "a".repeat(64)

    private fun manifest(
        version: String = "\"1.0.330\"",
        dmg: String = "\"https://github.com/KodeinKoders/KaTTY/releases/download/v1.0.330/KaTTY-1.0.330.dmg\"",
        size: String = "97012142",
        sha256: String = "\"$sha\"",
        extra: String = "",
    ) = """{ "version": $version, "dmg": $dmg, "size": $size, "sha256": $sha256,
        "notes": "https://github.com/KodeinKoders/KaTTY/releases/tag/v1.0.330"$extra }"""

    @Test
    fun versionsAreComparedNumberByNumber() {
        val v = { text: String -> AppVersion.parse(text)!! }
        assertTrue(v("1.0.330") > v("1.0.322"))
        assertTrue(v("1.0.1000") > v("1.0.999"))
        assertTrue(v("2.0.0") > v("1.9.999"))
        assertEquals(v("1.0"), v("1.0.0"))
        assertEquals("1.0.330", v("1.0.330").toString())
    }

    @Test
    fun aVersionThatIsNotOnlyNumbersIsNone() {
        for (text in listOf("", "1", "1.0.x", "1.0.330-dirty", "v1.0.330", "1..2", "1.0.99999999999")) {
            assertNull(AppVersion.parse(text), text)
        }
    }

    @Test
    fun aManifestIsRead() {
        val read = parseManifest(manifest(extra = """, "minimumSystem": "14.0""""))
        assertEquals(AppVersion.parse("1.0.330"), read.version)
        assertEquals(URI("https://github.com/KodeinKoders/KaTTY/releases/download/v1.0.330/KaTTY-1.0.330.dmg"), read.dmg)
        assertEquals(97012142L, read.size)
        assertEquals(sha, read.sha256)
        assertEquals(URI("https://github.com/KodeinKoders/KaTTY/releases/tag/v1.0.330"), read.notes)
    }

    @Test
    fun aManifestThatDoesNotSayWhatItClaimsIsRefused() {
        val refused = listOf(
            "not json",
            "[]",
            manifest(version = "\"1.0.x\""),
            manifest(dmg = "\"http://github.com/KaTTY-1.0.330.dmg\""),
            manifest(dmg = "\"file:///tmp/KaTTY-1.0.330.dmg\""),
            manifest(dmg = "\"https://github.com/KaTTY-1.0.331.dmg\""),
            manifest(dmg = "\"https://github.com/Other.dmg\""),
            manifest(size = "0"),
            manifest(size = "\"97012142\""),
            manifest(sha256 = "\"abc\""),
            manifest(sha256 = "\"${"z".repeat(64)}\""),
        )
        for (text in refused) assertFailsWith<UpdateManifestException>(text) { parseManifest(text) }
    }

    @Test
    fun aFileIsOnlyTakenFromAFeedOfATest() {
        val text = manifest(dmg = "\"file:///tmp/KaTTY-1.0.330.dmg\"")
        assertEquals(URI("file:///tmp/KaTTY-1.0.330.dmg"), parseManifest(text, allowFiles = true).dmg)
    }

    @Test
    fun aCheckIsDueOnceItsIntervalHasPassed() {
        val now = Instant.parse("2026-11-25T12:00:00Z")
        assertTrue(isCheckDue(null, now, UpdateCheck.DAILY))
        assertFalse(isCheckDue(now.minus(Duration.ofHours(23)), now, UpdateCheck.DAILY))
        assertTrue(isCheckDue(now.minus(Duration.ofHours(24)), now, UpdateCheck.DAILY))
        assertTrue(isCheckDue(now.minus(Duration.ofMinutes(61)), now, UpdateCheck.HOURLY))
        assertFalse(isCheckDue(now.minus(Duration.ofDays(6)), now, UpdateCheck.WEEKLY))
        assertTrue(isCheckDue(now.minus(Duration.ofDays(7)), now, UpdateCheck.WEEKLY))
        // The clock went back: the last check is not believed.
        assertTrue(isCheckDue(now.plus(Duration.ofDays(3)), now, UpdateCheck.WEEKLY))
    }

    @Test
    fun aManualCheckIsNeverDue() {
        assertFalse(isCheckDue(null, Instant.now(), UpdateCheck.MANUALLY))
    }

    @Test
    fun whatTheOrganizationImposesWinsAndIsSaid() {
        val settings = Settings(updateCheck = UpdateCheck.HOURLY, updateAction = UpdateAction.INSTALL_ON_QUIT)
        val own = updatePolicy(settings, ManagedUpdates.NONE)
        assertTrue(own.enabled)
        assertEquals(UpdateCheck.HOURLY, own.check)
        assertEquals(UpdateAction.INSTALL_ON_QUIT, own.action)
        assertEquals(URI(DEFAULT_UPDATE_FEED), own.feed)
        assertFalse(own.checkManaged || own.actionManaged)

        val managed = updatePolicy(
            settings,
            ManagedUpdates(check = UpdateCheck.WEEKLY, action = UpdateAction.NOTIFY, feed = "https://mirror.example.com/katty/latest.json"),
        )
        assertEquals(UpdateCheck.WEEKLY, managed.check)
        assertEquals(UpdateAction.NOTIFY, managed.action)
        assertTrue(managed.checkManaged && managed.actionManaged)
        assertEquals(URI("https://mirror.example.com/katty/latest.json"), managed.feed)

        assertFalse(updatePolicy(settings, ManagedUpdates(disabled = true)).enabled)
        assertTrue(updatePolicy(settings, ManagedUpdates(disabled = false)).enabled)
    }

    @Test
    fun aManagedFeedThatIsNotHttpsIsNotTaken() {
        assertEquals(URI(DEFAULT_UPDATE_FEED), updatePolicy(Settings(), ManagedUpdates(feed = "http://mirror.example.com/latest.json")).feed)
        assertEquals(URI(DEFAULT_UPDATE_FEED), updatePolicy(Settings(), ManagedUpdates(feed = "not an address")).feed)
    }

    @Test
    fun theManagedValuesAreReadByTheirNames() {
        assertEquals(UpdateCheck.WEEKLY, ManagedUpdates.checkNamed("Weekly"))
        assertEquals(UpdateAction.INSTALL_ON_QUIT, ManagedUpdates.actionNamed("install-on-quit"))
        assertNull(ManagedUpdates.checkNamed("monthly"))
        assertNull(ManagedUpdates.actionNamed(null))
    }

    @Test
    fun theDefaultsCheckDailyAndInstallOnQuit() {
        assertEquals(UpdateCheck.DAILY, Settings().updateCheck)
        assertEquals(UpdateAction.INSTALL_ON_QUIT, Settings().updateAction)
    }
}
