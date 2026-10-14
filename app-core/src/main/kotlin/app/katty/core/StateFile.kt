package app.katty.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("app.katty.core.StateFile")

/**
 * The file that keeps the state of the application (JSON, with the version of its format).
 *
 * Writing goes through a temporary file that is then moved over the real one, so that a crash does not leave half a file. A file that
 * cannot be read, or that a later version of the application wrote, is kept beside with a name that says why, and the application starts
 * empty rather than lose it by writing over it.
 */
public class StateFile(private val path: Path) {
    /** The state in the file, or an empty one when there is none, or it cannot be used. */
    public fun load(): KattyState {
        if (!Files.exists(path)) return KattyState()
        val text = try {
            Files.readString(path)
        } catch (e: IOException) {
            logger.error("Could not read {}", path, e)
            return keepAside("unreadable")
        }
        val version = try {
            JSON.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.int
        } catch (e: Exception) {
            logger.error("{} is not a state file", path, e)
            return keepAside("damaged")
        }
        if (version == null || version > KattyState.VERSION) {
            logger.warn("{} is of version {}, and this is version {}", path, version, KattyState.VERSION)
            return keepAside("version-$version")
        }
        return try {
            JSON.decodeFromString(KattyState.serializer(), text).let { it.copy(selection = it.selection.copy(maximized = null)) }.normalized()
        } catch (e: SerializationException) {
            logger.error("{} cannot be read", path, e)
            keepAside("damaged")
        } catch (e: IllegalArgumentException) {
            logger.error("{} cannot be read", path, e)
            keepAside("damaged")
        }
    }

    /** Writes [state], replacing what is there. The selection's maximized terminal is not written: it is not for the next launch. */
    public fun save(state: KattyState) {
        val text = JSON.encodeToString(KattyState.serializer(), state.copy(selection = state.selection.copy(maximized = null)))
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temporary, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // Moves the file to a name that says why, and starts empty.
    private fun keepAside(reason: String): KattyState {
        var aside = path.resolveSibling("${path.fileName}.$reason")
        var n = 1
        while (Files.exists(aside)) aside = path.resolveSibling("${path.fileName}.$reason.${n++}")
        try {
            Files.move(path, aside)
            logger.warn("Kept it as {}", aside)
        } catch (e: IOException) {
            logger.error("Could not move {} to {}", path, aside, e)
        }
        return KattyState()
    }

    private companion object {
        val JSON = Json {
            prettyPrint = true
            // What a later version added to a type is not an error, and the defaults are written so that the file says everything.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * Writes the state of [store] to [file] when it changes, after it has stayed the same for [delay], and once more when [flush] is called.
 * Dragging a divider changes the state many times a second, which is not worth as many writes.
 */
@OptIn(FlowPreview::class)
public class StatePersistence(
    private val store: KattyStore,
    private val file: StateFile,
    scope: CoroutineScope,
    delay: Duration = 500.milliseconds,
) {
    private val job: Job = scope.launch {
        store.state.debounce(delay).collect { write(it) }
    }

    /** Writes the state now: when the application quits. */
    public fun flush() {
        write(store.state.value)
    }

    /** Stops writing. */
    public fun stop() {
        job.cancel()
    }

    private fun write(state: KattyState) {
        try {
            file.save(state)
        } catch (e: IOException) {
            // The application works without it, and the next change tries again.
            logger.error("Could not save the state", e)
        }
    }
}
