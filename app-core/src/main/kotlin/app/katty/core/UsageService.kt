package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = LoggerFactory.getLogger("app.katty.core.Usage")

/** Where an agent's usage is read from: its own command, which does not cost a turn. */
public fun interface UsageSource {
    /** The reading, or `null` when it cannot be had: the agent is not logged in, its command failed, or it printed something unknown. */
    public suspend fun read(): UsageReading?
}

/** The result of a command that was run. */
public class CommandResult(public val exitCode: Int, public val output: String)

/** Runs a program, with what it needs to be told apart in a test. */
public fun interface CommandRunner {
    /** `null` when it could not be started, or did not end in [timeoutMs]. */
    public fun run(command: List<String>, directory: Path, timeoutMs: Long): CommandResult?
}

/** Runs the real programs: with nothing on their input, in [directory], and killed when they take longer than they are allowed. */
public object ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, directory: Path, timeoutMs: Long): CommandResult? =
        try {
            Files.createDirectories(directory)
            val process = ProcessBuilder(command).directory(directory.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // Read on another thread, or a program that prints a lot would block before the wait.
            val output = java.util.concurrent.atomic.AtomicReference("")
            val collect = Thread { output.set(process.inputStream.readBytes().decodeToString()) }.also { it.isDaemon = true; it.start() }
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                collect.join(2_000)
                CommandResult(process.exitValue(), output.get())
            } else {
                process.destroyForcibly()
                null
            }
        } catch (e: Exception) {
            logger.debug("Could not run {}", command, e)
            null
        }
}

/** `claude -p /usage`: Claude Code prints its usage without a turn, as text. */
public class ClaudeUsageSource(private val executable: Path, private val directory: Path, private val runner: CommandRunner = ProcessCommandRunner) : UsageSource {
    override suspend fun read(): UsageReading? = withContext(Dispatchers.IO) {
        runner.run(listOf(executable.toString(), "-p", "/usage", "--output-format", "json"), directory, TIMEOUT_MS)
            ?.takeIf { it.exitCode == 0 }?.let { UsageParsers.claude(it.output) }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * `agy -p /usage`: Antigravity prints its quota as JSON, and from version 1.1.11 without a turn: before that the command is a message to the
 * model, which costs, so older versions are not asked.
 */
public class AntigravityUsageSource(private val executable: Path, private val directory: Path, private val runner: CommandRunner = ProcessCommandRunner) : UsageSource {
    override suspend fun read(): UsageReading? = withContext(Dispatchers.IO) {
        val version = runner.run(listOf(executable.toString(), "--version"), directory, 5_000)?.takeIf { it.exitCode == 0 }?.output ?: return@withContext null
        if (!isAtLeast(version, MINIMUM)) return@withContext null
        runner.run(listOf(executable.toString(), "-p", "/usage", "--output-format", "json", "--print-timeout", "20s"), directory, 30_000)
            ?.takeIf { it.exitCode == 0 }?.let { UsageParsers.antigravity(it.output) }
    }

    internal companion object {
        val MINIMUM = listOf(1, 1, 11)

        /** Whether the first version in [text] (`1.3.5`, `agy 1.3.5`) is [minimum] or later. */
        fun isAtLeast(text: String, minimum: List<Int>): Boolean {
            val found = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(text)?.destructured?.toList()?.map { it.toInt() } ?: return false
            for (i in minimum.indices) if (found[i] != minimum[i]) return found[i] > minimum[i]
            return true
        }
    }
}

/**
 * `codex app-server`: Codex answers a JSON-RPC request for its rate limits, one JSON object a line. It is started for the question and
 * ended after the answer.
 *
 * @param command What starts the server: the program, and its argument.
 */
public class CodexUsageSource(private val command: List<String>, private val directory: Path, private val timeoutMs: Long = 20_000) : UsageSource {
    override suspend fun read(): UsageReading? = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(directory)
            val process = ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            // Reading a line waits for as long as the server is silent: ending the process is what ends the wait.
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    process.destroyForcibly()
                } catch (e: InterruptedException) {
                    // The answer came in time.
                }
            }.also { it.isDaemon = true; it.start() }
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                fun send(message: JsonObject) {
                    input.write(message.toString())
                    input.newLine()
                    input.flush()
                }
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                        put("params", buildJsonObject { put("clientInfo", buildJsonObject { put("name", "katty"); put("version", "0.1.0") }) })
                    },
                )
                var sent = false
                while (System.nanoTime() < deadline) {
                    val line = output.readLine() ?: return@withContext null
                    val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val id = (message["id"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } ?: continue
                    if (id == 1 && !sent) {
                        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "initialized"); put("params", buildJsonObject {}) })
                        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "account/rateLimits/read"); put("params", buildJsonObject {}) })
                        sent = true
                    } else if (id == 2) {
                        return@withContext message["result"]?.let(UsageParsers::codex)
                    }
                }
                null
            } finally {
                watchdog.interrupt()
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.debug("Could not read the usage of Codex", e)
            null
        }
    }
}

/**
 * The usage of the agents that are shown, read from their own commands and read again every [interval]: a reading that fails is no reading,
 * and an agent that has no source has no meter. Nothing is read for an agent that is not wanted.
 */
public class UsageService(
    private val scope: CoroutineScope,
    private val sources: Map<Agent, UsageSource>,
    private val interval: Duration = 2.minutes,
) {
    private val mutableReadings = MutableStateFlow<Map<Agent, UsageReading>>(emptyMap())

    /** What the agents say of their usage, for the ones that were read. */
    public val readings: StateFlow<Map<Agent, UsageReading>> get() = mutableReadings

    private val mutableRefreshing = MutableStateFlow(false)

    /** Whether the agents are being read: the host shows that the refresh is not available while it is. */
    public val refreshing: StateFlow<Boolean> get() = mutableRefreshing

    private var wanted: Set<Agent> = emptySet()
    private var loop: Job? = null
    private val reading = Mutex()

    /** Reads [agents] now and from then on. Agents that are no longer wanted lose their reading. */
    @Synchronized
    public fun want(agents: Set<Agent>) {
        val relevant = agents.filter { it in sources }.toSet()
        if (relevant == wanted && loop != null) return
        wanted = relevant
        mutableReadings.value = mutableReadings.value.filterKeys { it in relevant }
        restart()
    }

    /**
     * Reads the wanted agents now, which is the same as the loop doing it, and then every [interval] from this reading. Nothing happens while
     * they are being read: a second request would only queue the commands behind the first.
     */
    @Synchronized
    public fun refreshNow() {
        if (wanted.isEmpty() || mutableRefreshing.value) return
        restart()
    }

    // The loop is made again so that the next reading is an interval after this one, wherever the last one was.
    private fun restart() {
        loop?.cancel()
        loop = if (wanted.isEmpty()) null else scope.launch {
            while (true) {
                refresh()
                delay(interval)
            }
        }
    }

    /** Reads the wanted agents again, one after the other: they are slow, and each is a program that starts. */
    public suspend fun refresh() {
        reading.withLock {
            if (wanted.isEmpty()) return
            mutableRefreshing.value = true
            try {
                readWanted()
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    private suspend fun readWanted() {
        for (agent in wanted) {
            val source = sources[agent] ?: continue
            val reading = try {
                source.read()
            } catch (e: Exception) {
                logger.debug("Could not read the usage of {}", agent, e)
                null
            }
            mutableReadings.value = if (reading == null) mutableReadings.value - agent else mutableReadings.value + (agent to reading)
        }
    }

    /** Stops reading. */
    @Synchronized
    public fun stop() {
        loop?.cancel()
        loop = null
    }
}

/**
 * What OpenCode (or Kilo Code, whose CLI is OpenCode's) spent today, over the last 7 days and this month, from its own database
 * ([OpenCodeStore.spend]): what the assistant's answers cost, in all the sessions. A period that cannot be read is no reading for the agent.
 *
 * @param agent Whose database it is.
 * @param zone Where a day and a month start.
 */
public class OpenCodeUsageSource internal constructor(
    private val store: OpenCodeStore,
    private val clock: () -> Instant,
    private val zone: ZoneId,
    private val agent: Agent = Agent.OPENCODE,
) : UsageSource {
    public constructor() : this(OpenCodeStore(), Instant::now, ZoneId.systemDefault())

    override suspend fun read(): UsageReading? = withContext(Dispatchers.IO) {
        val now = clock().atZone(zone)
        val today = now.toLocalDate().atStartOfDay(zone)
        val periods = listOf(
            "Today" to today,
            "7 days" to now.minusDays(7),
            "This month" to today.withDayOfMonth(1),
        )
        val spend = periods.map { (label, since) -> UsageSpend(label, store.spend(since.toInstant().toEpochMilli()) ?: return@withContext null) }
        UsageReading(agent, windows = emptyList(), spend = spend)
    }
}

/**
 * What omp spent today, over the last 7 days and this month, from its own statistics database (`~/.omp/stats.db`): what each of its requests cost.
 *
 * Its `omp stats -j` prints the last 24 hours and nothing else (it has no option for a range, and the series are by hour and by day in UTC), so the periods
 * are not read from what it prints. What the command does that matters is **to bring the database up to date**: omp does not keep it while it runs, it reads the
 * files of the sessions when `omp stats` runs, from where it left (a third of a second when there is nothing new). So the command is run, its output is left
 * alone, and the database is read, **read-only**, with the same periods as the other agents. A command that fails, or a database that cannot be read, is no
 * reading for the agent: a database that is not up to date would be a wrong number.
 *
 * @param zone Where a day and a month start.
 */
public class OmpUsageSource internal constructor(
    private val executable: Path,
    private val directory: Path,
    private val database: Path,
    private val runner: CommandRunner,
    private val clock: () -> Instant,
    private val zone: ZoneId,
) : UsageSource {
    public constructor(executable: Path, directory: Path) : this(
        executable, directory, Path.of(System.getProperty("user.home"), ".omp", "stats.db"), ProcessCommandRunner, Instant::now, ZoneId.systemDefault(),
    )

    override suspend fun read(): UsageReading? = withContext(Dispatchers.IO) {
        runner.run(listOf(executable.toString(), "stats", "-j"), directory, TIMEOUT_MS)?.takeIf { it.exitCode == 0 } ?: return@withContext null
        if (!Files.exists(database)) return@withContext null
        val now = clock().atZone(zone)
        val today = now.toLocalDate().atStartOfDay(zone)
        val periods = listOf(
            "Today" to today,
            "7 days" to now.minusDays(7),
            "This month" to today.withDayOfMonth(1),
        )
        val spend = try {
            SqliteDatabase.open(database).use { db ->
                periods.map { (label, since) ->
                    UsageSpend(label, db.query("SELECT COALESCE(SUM(cost_total), 0) FROM messages WHERE timestamp >= ?", since.toInstant().toEpochMilli()) { it.double(0) ?: 0.0 }.first())
                }
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        UsageReading(Agent.OMP, windows = emptyList(), spend = spend)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/** The sources of the agents that are installed, which have a command that reports their usage. */
public fun usageSources(detected: Map<Agent, Path>, directory: Path): Map<Agent, UsageSource> = buildMap {
    detected[Agent.CLAUDE]?.let { put(Agent.CLAUDE, ClaudeUsageSource(it, directory)) }
    detected[Agent.ANTIGRAVITY]?.let { put(Agent.ANTIGRAVITY, AntigravityUsageSource(it, directory)) }
    detected[Agent.CODEX]?.let { put(Agent.CODEX, CodexUsageSource(listOf(it.toString(), "app-server"), directory)) }
    // It has no allowance: what it spent is read from its database.
    detected[Agent.OPENCODE]?.let { put(Agent.OPENCODE, OpenCodeUsageSource()) }
    // It has no allowance either: what it spent is read from its statistics database, which its own command brings up to date.
    detected[Agent.OMP]?.let { put(Agent.OMP, OmpUsageSource(it, directory)) }
    detected[Agent.KILO]?.let { put(Agent.KILO, OpenCodeUsageSource(OpenCodeStore.kilo(), Instant::now, ZoneId.systemDefault(), Agent.KILO)) }
}
