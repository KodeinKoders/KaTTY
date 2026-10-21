package app.katty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime

private val logger = LoggerFactory.getLogger("app.katty.core.ModelLimits")

/**
 * The size of the context window of the models, from the list that OpenCode keeps (`~/.cache/opencode/models.json`, the one of models.dev: each
 * provider has `models`, each with a `limit.context`). The file is read again when it has changed. OpenCode's own configuration is never read:
 * it may hold keys.
 */
internal class ModelLimits(private val file: Path = defaultFile()) {
    private var modified: Long = -1
    private var limits: Map<Pair<String, String>, Int> = emptyMap()

    /** The window of [model] of [provider] in tokens, or `null` when the list does not have it. */
    @Synchronized
    fun contextOf(provider: String, model: String): Int? {
        refresh()
        return limits[provider to model]
    }

    private fun refresh() {
        val time = try {
            file.getLastModifiedTime().toMillis()
        } catch (_: Exception) {
            limits = emptyMap()
            modified = -1
            return
        }
        if (time == modified) return
        modified = time
        limits = try {
            parse(Files.readString(file))
        } catch (e: Exception) {
            logger.debug("Could not read {}", file, e)
            emptyMap()
        }
    }

    internal companion object {
        fun defaultFile(): Path =
            Path.of(System.getProperty("user.home"), ".cache", "opencode", "models.json")

        /** Kilo Code's, which is OpenCode's CLI with a folder of its own. */
        fun kiloFile(): Path =
            Path.of(System.getProperty("user.home"), ".cache", "kilo", "models.json")

        fun parse(text: String): Map<Pair<String, String>, Int> {
            val result = mutableMapOf<Pair<String, String>, Int>()
            for ((provider, entry) in Json.parseToJsonElement(text).jsonObject) {
                val models = (entry as? JsonObject)?.get("models") as? JsonObject ?: continue
                for ((model, description) in models) {
                    val context = ((description as? JsonObject)?.get("limit") as? JsonObject)?.get("context") ?: continue
                    context.jsonPrimitive.content.toIntOrNull()?.takeIf { it > 0 }?.let { result[provider to model] = it }
                }
            }
            return result
        }
    }
}
