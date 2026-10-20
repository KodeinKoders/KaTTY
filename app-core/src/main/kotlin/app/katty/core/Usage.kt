package app.katty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * How much of an allowance an agent has used.
 *
 * @param label What the allowance is called: `Session`, `Week`…
 * @param percentUsed From 0 to 100.
 * @param resetsAt When it starts again, `null` when the agent did not say.
 */
public data class UsageWindow(val label: String, val percentUsed: Int, val resetsAt: Instant?)

/**
 * What an agent has spent in a period, for an agent that has no allowance to meter.
 *
 * @param label What the period is called: `Today`, `7 days`, `This month`.
 * @param usd What it cost, in dollars, as the agent counts it.
 */
public data class UsageSpend(val label: String, val usd: Double)

/**
 * What an agent says of its usage: each of its allowances, which the widget shows with a bar of its own, and what it spent, for the agents that
 * count money and have no allowance. [primary] is the allowance that is the nearest to being used up.
 */
public data class UsageReading(val agent: Agent, val windows: List<UsageWindow>, val spend: List<UsageSpend> = emptyList()) {
    /** The allowance that is the nearest to being used up. */
    val primary: UsageWindow? get() = windows.maxByOrNull { it.percentUsed }
}

/** The parsers of what the agents' own commands print. They read text that nothing promises to keep as it is: what they cannot read is no reading. */
public object UsageParsers {
    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * What `claude -p /usage --output-format json` prints: a JSON object whose `result` is text, with a line for each allowance:
     * `Current session: 26% used · resets Oct 15 at 5:49am (Europe/Paris)`.
     *
     * @param now When it was read: a reset that has no year or day is the next time that the clock says so.
     */
    public fun claude(output: String, now: Instant = Instant.now()): UsageReading? {
        val text = try {
            JSON.parseToJsonElement(output).jsonObject["result"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        } ?: return null
        val line = Regex("^\\s*(Current [^:]+?):\\s*(\\d{1,3})% used(?:\\s*[·•-]\\s*resets\\s+(.+?))?\\s*$")
        val windows = text.lines().mapNotNull { raw ->
            val match = line.find(raw) ?: return@mapNotNull null
            val (label, percent, reset) = match.destructured
            UsageWindow(label.removePrefix("Current ").replaceFirstChar { it.uppercase() }, percent.toInt().coerceIn(0, 100), reset.takeIf { it.isNotEmpty() }?.let { parseReset(it, now) })
        }
        return windows.takeIf { it.isNotEmpty() }?.let { UsageReading(Agent.CLAUDE, it) }
    }

    /**
     * `Oct 15 at 5:49am (Europe/Paris)`, or `5:49am (Europe/Paris)` for the next time the clock says so. `null` for a text that is neither.
     */
    internal fun parseReset(text: String, now: Instant): Instant? {
        val match = Regex("^(?:([A-Za-z]{3,9})\\.?\\s+(\\d{1,2})\\s+at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)\\s*\\(([^)]+)\\)\\s*$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        val (monthName, day, hour12, minute, meridiem, zoneName) = match.destructured
        return try {
            val zone = ZoneId.of(zoneName)
            val current = ZonedDateTime.ofInstant(now, zone)
            var hour = hour12.toInt() % 12 + if (meridiem.equals("pm", true)) 12 else 0
            val base = current.withHour(hour).withMinute(minute.ifEmpty { "0" }.toInt()).withSecond(0).withNano(0)
            val result = if (monthName.isEmpty()) {
                if (base.toInstant().isAfter(now)) base else base.plusDays(1)
            } else {
                val month = java.time.Month.entries.firstOrNull { it.name.startsWith(monthName.take(3).uppercase()) } ?: return null
                var date = base.withMonth(month.value).withDayOfMonth(day.toInt())
                // A date that is long past is next year's.
                if (date.toInstant().isBefore(now.minusSeconds(24 * 3600))) date = date.plusYears(1)
                date
            }
            result.toInstant()
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * What `agy -p /usage --output-format json` prints: groups of models, each with buckets that say the fraction that is left and when it
     * is refilled.
     */
    public fun antigravity(output: String): UsageReading? {
        val groups = try {
            JSON.parseToJsonElement(output).jsonObject["command"]?.jsonObject?.get("data")?.jsonObject?.get("groups")?.jsonArray
        } catch (e: Exception) {
            null
        } ?: return null
        val windows = groups.flatMap { group ->
            val name = group.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "Quota"
            (group.jsonObject["buckets"] as? JsonArray).orEmpty().mapNotNull { bucket ->
                val remaining = bucket.jsonObject["remaining_fraction"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val reset = bucket.jsonObject["reset_time"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
                UsageWindow(name, ((1.0 - remaining) * 100).roundToInt().coerceIn(0, 100), reset)
            }
        }
        return windows.takeIf { it.isNotEmpty() }?.let { UsageReading(Agent.ANTIGRAVITY, it) }
    }

    /**
     * The answer to `account/rateLimits/read` of `codex app-server`: a `primary` and a `secondary` window, each with the percent used, how
     * long it lasts and when it resets (in seconds since the epoch).
     */
    public fun codex(result: JsonElement): UsageReading? {
        val limits = (result as? JsonObject)?.get("rateLimits") as? JsonObject ?: return null
        val windows = listOf("primary", "secondary").mapNotNull { key ->
            val window = limits[key] as? JsonObject ?: return@mapNotNull null
            val percent = (window["usedPercent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val minutes = (window["windowDurationMins"] as? JsonPrimitive)?.doubleOrNull?.toLong()
            val reset = (window["resetsAt"] as? JsonPrimitive)?.doubleOrNull?.toLong()?.let(Instant::ofEpochSecond)
            UsageWindow(codexLabel(minutes), percent.roundToInt().coerceIn(0, 100), reset)
        }
        return windows.takeIf { it.isNotEmpty() }?.let { UsageReading(Agent.CODEX, it) }
    }

    private fun codexLabel(minutes: Long?): String = when {
        minutes == null -> "Usage"
        minutes in 280..320 -> "Session"
        minutes in 10_000..10_200 -> "Week"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "$minutes min"
    }
}

/** How long until [resetsAt], as the usage panel says it: `53 minutes`, `2 hours 10 minutes`, `3 days 4 hours`. `null` when it is not known. */
public fun resetsIn(now: Instant, resetsAt: Instant?): String? {
    if (resetsAt == null) return null
    val minutes = ((resetsAt.epochSecond - now.epochSecond) / 60).coerceAtLeast(0)
    fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"}"
    return when {
        minutes < 60 -> unit(minutes, "minute")
        minutes < 24 * 60 -> unit(minutes / 60, "hour") + (minutes % 60).takeIf { it > 0 }?.let { " " + unit(it, "minute") }.orEmpty()
        else -> unit(minutes / (24 * 60), "day") + (minutes % (24 * 60) / 60).takeIf { it > 0 }?.let { " " + unit(it, "hour") }.orEmpty()
    }
}
