package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.CrushStore")

/**
 * What Crush keeps of its sessions, read from the SQLite database of the project it runs in (`<project>/.crush/crush.db`), **read-only**.
 * Crush has one database for each project, and says where it is in `~/.local/share/crush/projects.json` (`path` and `data_dir`): that is
 * asked first, and `<project>/.crush` is the answer for a project that is not there. A read that fails (no database, another schema, a lock
 * that does not go) gives `null`: no reading, never a wrong one.
 *
 * A Crush started with `--data-dir` keeps its database elsewhere, which nothing says: it is not found, and starts a new conversation.
 */
internal class CrushStore(private val projects: Path = defaultProjects()) {
    /**
     * The session that the Crush that runs in [cwd] and started at [startedAtMs] is in: the newest one (by its last update) of that project,
     * created or updated since the process started, that has a message (Crush makes the session with the first one, but a session with none
     * has nothing to resume). A session that another one started (a sub-agent's) is not the terminal's. The caller knows whether another Crush
     * runs in the same directory, in which case this cannot say which one is whose.
     */
    fun sessionFor(cwd: String, startedAtMs: Long): String? {
        val database = dataDirectory(cwd).resolve("crush.db")
        if (!Files.exists(database)) return null
        // Crush counts in seconds.
        val since = startedAtMs / 1000
        return try {
            SqliteDatabase.open(database).use { db ->
                db.query(
                    "SELECT id FROM sessions WHERE (parent_session_id IS NULL OR parent_session_id = '') AND message_count > 0 " +
                        "AND (created_at >= ? OR updated_at >= ?) ORDER BY updated_at DESC LIMIT 1",
                    since, since,
                ) { it.text(0)!! }.firstOrNull()
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", database, e)
            null
        }
    }

    private fun dataDirectory(cwd: String): Path {
        val listed = try {
            (Json.parseToJsonElement(Files.readString(projects)).jsonObject["projects"] as? JsonArray)?.firstNotNullOfOrNull { project ->
                val record = project.jsonObject
                record["data_dir"]?.jsonPrimitive?.contentOrNull?.takeIf { record["path"]?.jsonPrimitive?.contentOrNull == cwd }
            }
        } catch (e: Exception) {
            null
        }
        return Path.of(listed ?: "$cwd/.crush")
    }

    internal companion object {
        fun defaultProjects(): Path = Path.of(System.getProperty("user.home"), ".local", "share", "crush", "projects.json")
    }
}
