package app.katty.core

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTest {
    // What `claude -p /usage --output-format json` printed on the machine of the owner (the text of `result`, in JSON).
    private val claudeOutput = """{"type":"result","subtype":"success","result":"You are currently using your subscription to power your Claude Code usage\n\nCurrent session: 26% used · resets Oct 15 at 5:49am (Europe/Paris)\nCurrent week (all models): 72% used · resets Oct 16 at 10:59am (Europe/Paris)\n\nWhat's contributing to your limits usage?\nApproximate, based on local sessions on this machine\n\nLast 24h · 1211 requests · 6 sessions\n  95% of your usage came from subagent-heavy sessions"}"""

    // And `agy -p /usage --output-format json --print-timeout 20s`.
    private val antigravityOutput = """{"conversation_id":"","status":"SUCCESS","response":"Gemini Models\tWeekly Limit Remaining\t83%\t2026-10-21T16:53:27Z\n","num_turns":0,"command":{"name":"usage","data":{"groups":[{"name":"Gemini Models","buckets":[{"id":"gemini-weekly","name":"Weekly Limit Remaining","window":"weekly","remaining_fraction":0.8302823901176453,"reset_time":"2026-10-21T16:53:27Z"}]},{"name":"Claude and GPT models","buckets":[{"id":"3p-weekly","name":"Weekly Limit Remaining","window":"weekly","remaining_fraction":1,"reset_time":"2026-10-21T23:17:05Z"}]}]}}}"""

    private val now = Instant.parse("2026-10-15T01:00:00Z")

    @Test
    fun readsTheTwoAllowancesOfClaude() {
        val reading = UsageParsers.claude(claudeOutput, now)!!

        assertEquals(Agent.CLAUDE, reading.agent)
        assertEquals(listOf("Session", "Week (all models)"), reading.windows.map { it.label })
        assertEquals(listOf(26, 72), reading.windows.map { it.percentUsed })
        // 5:49 in Paris, in summer time, is 03:49 UTC.
        assertEquals(Instant.parse("2026-10-15T03:49:00Z"), reading.windows[0].resetsAt)
        assertEquals(Instant.parse("2026-10-16T08:59:00Z"), reading.windows[1].resetsAt)
        assertEquals(72, reading.primary!!.percentUsed)
    }

    @Test
    fun aResetThatHasNoDayIsTheNextTimeTheClockSaysSo() {
        // 5:49am in Paris: 03:49 UTC, which is after 01:00; and 12:00am is the next midnight.
        assertEquals(Instant.parse("2026-10-15T03:49:00Z"), UsageParsers.parseReset("5:49am (Europe/Paris)", now))
        assertEquals(Instant.parse("2026-10-15T22:00:00Z"), UsageParsers.parseReset("12am (Europe/Paris)", now))
        assertEquals(Instant.parse("2026-10-16T01:00:00Z"), UsageParsers.parseReset("1am (UTC)", now))
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"), UsageParsers.parseReset("1pm (UTC)", now))
    }

    @Test
    fun aResetThatIsNotUnderstoodIsNoReset() {
        assertNull(UsageParsers.parseReset("tomorrow", now))
        assertNull(UsageParsers.parseReset("5:49am (Nowhere/Land)", now))
    }

    @Test
    fun aDateEarlierThanNowIsNextYear() {
        assertEquals(Instant.parse("2027-01-05T09:00:00Z"), UsageParsers.parseReset("Jan 5 at 9am (UTC)", Instant.parse("2026-10-15T01:00:00Z")))
    }

    @Test
    fun anAllowanceWithoutAResetIsStillAReading() {
        val reading = UsageParsers.claude("""{"result":"Current session: 5% used"}""", now)!!

        assertEquals(5, reading.windows.single().percentUsed)
        assertNull(reading.windows.single().resetsAt)
    }

    @Test
    fun anOutputThatHasNoAllowanceIsNoReading() {
        assertNull(UsageParsers.claude("""{"result":"You are using an API key: there are no limits to show."}""", now))
        assertNull(UsageParsers.claude("not json", now))
        assertNull(UsageParsers.claude("""{"result": 5}""", now))
        assertNull(UsageParsers.claude("", now))
    }

    @Test
    fun readsTheQuotaOfAntigravityAsWhatIsUsed() {
        val reading = UsageParsers.antigravity(antigravityOutput)!!

        assertEquals(Agent.ANTIGRAVITY, reading.agent)
        assertEquals(listOf("Gemini Models", "Claude and GPT models"), reading.windows.map { it.label })
        assertEquals(listOf(17, 0), reading.windows.map { it.percentUsed })
        assertEquals(Instant.parse("2026-10-21T16:53:27Z"), reading.windows[0].resetsAt)
        assertEquals("Gemini Models", reading.primary!!.label)
    }

    @Test
    fun anOutputOfAntigravityThatIsNotAQuotaIsNoReading() {
        assertNull(UsageParsers.antigravity("""{"status":"SUCCESS","response":"hello"}"""))
        assertNull(UsageParsers.antigravity("nothing"))
    }

    @Test
    fun readsTheRateLimitsOfCodex() {
        val result = Json.parseToJsonElement("""{"rateLimits":{"primary":{"usedPercent":41.4,"windowDurationMins":300,"resetsAt":1792000000},"secondary":{"usedPercent":7,"windowDurationMins":10080,"resetsAt":1792500000}}}""")

        val reading = UsageParsers.codex(result)!!

        assertEquals(Agent.CODEX, reading.agent)
        assertEquals(listOf("Session", "Week"), reading.windows.map { it.label })
        assertEquals(listOf(41, 7), reading.windows.map { it.percentUsed })
        assertEquals(Instant.ofEpochSecond(1792000000), reading.windows[0].resetsAt)
    }

    @Test
    fun aWindowOfAnotherLengthIsNamedByIt() {
        val result = Json.parseToJsonElement("""{"rateLimits":{"primary":{"usedPercent":1,"windowDurationMins":120},"secondary":{"usedPercent":2,"windowDurationMins":45}}}""")

        assertEquals(listOf("2 h", "45 min"), UsageParsers.codex(result)!!.windows.map { it.label })
    }

    @Test
    fun noRateLimitsIsNoReading() {
        assertNull(UsageParsers.codex(Json.parseToJsonElement("""{"rateLimits":null}""")))
        assertNull(UsageParsers.codex(Json.parseToJsonElement("""{}""")))
    }

    @Test
    fun theTimeToTheResetIsToldInTheTwoLargestUnits() {
        val base = Instant.parse("2026-10-15T00:00:00Z")
        assertEquals("53 minutes", resetsIn(base, base.plusSeconds(53 * 60)))
        assertEquals("1 minute", resetsIn(base, base.plusSeconds(60)))
        assertEquals("2 hours 10 minutes", resetsIn(base, base.plusSeconds((2 * 60 + 10) * 60)))
        assertEquals("5 hours", resetsIn(base, base.plusSeconds(5 * 3600)))
        assertEquals("3 days 4 hours", resetsIn(base, base.plusSeconds((3 * 24 + 4) * 3600)))
        assertEquals("11 days", resetsIn(base, base.plusSeconds(11 * 24 * 3600)))
        assertEquals("0 minutes", resetsIn(base, base.minusSeconds(100)))
        assertNull(resetsIn(base, null))
    }

    @Test
    fun theVersionOfAntigravityThatCanAnswerForFree() {
        assertTrue(AntigravityUsageSource.isAtLeast("1.3.5", AntigravityUsageSource.MINIMUM))
        assertTrue(AntigravityUsageSource.isAtLeast("agy 1.1.11\n", AntigravityUsageSource.MINIMUM))
        assertTrue(AntigravityUsageSource.isAtLeast("2.0.0", AntigravityUsageSource.MINIMUM))
        assertFalse(AntigravityUsageSource.isAtLeast("1.1.10", AntigravityUsageSource.MINIMUM))
        assertFalse(AntigravityUsageSource.isAtLeast("0.9.99", AntigravityUsageSource.MINIMUM))
        assertFalse(AntigravityUsageSource.isAtLeast("no version", AntigravityUsageSource.MINIMUM))
    }
}
