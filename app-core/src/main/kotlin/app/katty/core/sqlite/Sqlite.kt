package app.katty.core.sqlite

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.sqlite.Sqlite")

// The flags of sqlite3_open_v2.
private const val SQLITE_OPEN_READONLY = 0x01
private const val SQLITE_OPEN_READWRITE = 0x02
private const val SQLITE_OPEN_CREATE = 0x04

private const val SQLITE_OK = 0
private const val SQLITE_ROW = 100
private const val SQLITE_DONE = 101

private const val SQLITE_NULL = 5

// Another process (OpenCode) writes to the files that are read: wait this long for its lock instead of failing at once.
private const val BUSY_TIMEOUT_MS = 1000

/** A call of the SQLite library that failed, or the library that is not there. */
internal class SqliteException(message: String, cause: Throwable? = null) : Exception(message, cause)

// The system's SQLite, which macOS has had since its first version: nothing to ship and no dependency. `dlopen` finds it in the shared cache
// of the system even though there is no file at this path.
private object Native {
    private val linker = Linker.nativeLinker()
    private val lookup = SymbolLookup.libraryLookup("/usr/lib/libsqlite3.dylib", Arena.global())

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { SqliteException("$name is not in libsqlite3") }, descriptor)

    private val ADDRESS = ValueLayout.ADDRESS
    private val INT = ValueLayout.JAVA_INT
    private val LONG = ValueLayout.JAVA_LONG
    private val DOUBLE = ValueLayout.JAVA_DOUBLE

    val open: MethodHandle by lazy { handle("sqlite3_open_v2", FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT, ADDRESS)) }
    val close: MethodHandle by lazy { handle("sqlite3_close_v2", FunctionDescriptor.of(INT, ADDRESS)) }
    val busyTimeout: MethodHandle by lazy { handle("sqlite3_busy_timeout", FunctionDescriptor.of(INT, ADDRESS, INT)) }
    val errmsg: MethodHandle by lazy { handle("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    val prepare: MethodHandle by lazy { handle("sqlite3_prepare_v2", FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT, ADDRESS, ADDRESS)) }
    val bindText: MethodHandle by lazy { handle("sqlite3_bind_text", FunctionDescriptor.of(INT, ADDRESS, INT, ADDRESS, INT, ADDRESS)) }
    val bindInt64: MethodHandle by lazy { handle("sqlite3_bind_int64", FunctionDescriptor.of(INT, ADDRESS, INT, LONG)) }
    val step: MethodHandle by lazy { handle("sqlite3_step", FunctionDescriptor.of(INT, ADDRESS)) }
    val columnType: MethodHandle by lazy { handle("sqlite3_column_type", FunctionDescriptor.of(INT, ADDRESS, INT)) }
    val columnText: MethodHandle by lazy { handle("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, INT)) }
    val columnInt64: MethodHandle by lazy { handle("sqlite3_column_int64", FunctionDescriptor.of(LONG, ADDRESS, INT)) }
    val columnDouble: MethodHandle by lazy { handle("sqlite3_column_double", FunctionDescriptor.of(DOUBLE, ADDRESS, INT)) }
    val finalize: MethodHandle by lazy { handle("sqlite3_finalize", FunctionDescriptor.of(INT, ADDRESS)) }
}

/** The row that a statement is on. It is only valid inside the function that [SqliteDatabase.query] gave it to. */
internal class SqliteRow internal constructor(private val statement: MemorySegment) {
    fun isNull(column: Int): Boolean = Native.columnType.invokeExact(statement, column) as Int == SQLITE_NULL

    fun text(column: Int): String? {
        if (isNull(column)) return null
        val pointer = Native.columnText.invokeExact(statement, column) as MemorySegment
        return if (pointer == MemorySegment.NULL) null else pointer.reinterpret(Long.MAX_VALUE).getString(0)
    }

    fun long(column: Int): Long? = if (isNull(column)) null else Native.columnInt64.invokeExact(statement, column) as Long

    fun double(column: Int): Double? = if (isNull(column)) null else Native.columnDouble.invokeExact(statement, column) as Double
}

/**
 * A SQLite database opened through the system's `libsqlite3` with the FFM API. It is the minimum that reading another program's database needs:
 * statements with text and integer parameters, and rows read column by column. A connection is not thread-safe: open one for each use.
 */
internal class SqliteDatabase private constructor(private val handle: MemorySegment) : AutoCloseable {
    /** The SQL of [sql], which has no result, such as the one that makes a table (for the tests, which open a database for writing). */
    fun execute(sql: String, vararg parameters: Any) {
        query(sql, *parameters) { }
    }

    /**
     * Runs [sql] with its `?` [parameters] (a [String], or a [Long] or [Int]) and gives each row to [map].
     * @throws SqliteException when the statement is wrong, or the database cannot be read.
     */
    fun <T> query(sql: String, vararg parameters: Any, map: (SqliteRow) -> T): List<T> = Arena.ofConfined().use { arena ->
        val out = arena.allocate(ValueLayout.ADDRESS)
        check(Native.prepare.invokeExact(handle, arena.allocateFrom(sql), -1, out, MemorySegment.NULL) as Int, sql)
        val statement = out.get(ValueLayout.ADDRESS, 0)
        try {
            parameters.forEachIndexed { index, parameter ->
                val result = when (parameter) {
                    is String -> Native.bindText.invokeExact(statement, index + 1, arena.allocateFrom(parameter), -1, MemorySegment.ofAddress(-1)) as Int
                    is Long -> Native.bindInt64.invokeExact(statement, index + 1, parameter) as Int
                    is Int -> Native.bindInt64.invokeExact(statement, index + 1, parameter.toLong()) as Int
                    else -> throw SqliteException("A parameter of type ${parameter::class.simpleName} is not supported")
                }
                check(result, sql)
            }
            val rows = mutableListOf<T>()
            val row = SqliteRow(statement)
            while (true) {
                when (val result = Native.step.invokeExact(statement) as Int) {
                    SQLITE_ROW -> rows += map(row)
                    SQLITE_DONE -> break
                    else -> check(result, sql)
                }
            }
            rows
        } finally {
            Native.finalize.invokeExact(statement) as Int
        }
    }

    private fun check(result: Int, sql: String) {
        if (result == SQLITE_OK) return
        val message = (Native.errmsg.invokeExact(handle) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
        throw SqliteException("SQLite said $message ($result) for: $sql")
    }

    override fun close() {
        Native.close.invokeExact(handle) as Int
    }

    internal companion object {
        /**
         * What [block] reads from the database at [path], opened read-only: `null` when there is no file there, or when it cannot be read (it
         * is another program's, which may be writing it, or of a version that has other tables), which is logged at the debug level. A wrong
         * answer is never what a failure gives.
         */
        fun <T> read(path: Path, block: (SqliteDatabase) -> T?): T? {
            if (!Files.exists(path)) return null
            return try {
                open(path).use(block)
            } catch (e: Exception) {
                logger.debug("Could not read {}", path, e)
                null
            }
        }

        /**
         * Opens the database at [path], **read-only** unless [readOnly] is `false`, which creates it: that is for the tests.
         * @throws SqliteException when it cannot be opened, or the library cannot be loaded.
         */
        fun open(path: Path, readOnly: Boolean = true): SqliteDatabase {
            val flags = if (readOnly) SQLITE_OPEN_READONLY else SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
            try {
                Arena.ofConfined().use { arena ->
                    val out = arena.allocate(ValueLayout.ADDRESS)
                    val result = Native.open.invokeExact(arena.allocateFrom(path.toString()), out, flags, MemorySegment.NULL) as Int
                    val handle = out.get(ValueLayout.ADDRESS, 0)
                    if (result != SQLITE_OK) {
                        Native.close.invokeExact(handle) as Int
                        throw SqliteException("SQLite could not open $path ($result)")
                    }
                    Native.busyTimeout.invokeExact(handle, BUSY_TIMEOUT_MS) as Int
                    return SqliteDatabase(handle)
                }
            } catch (e: SqliteException) {
                throw e
            } catch (e: Throwable) {
                throw SqliteException("SQLite could not be used", e)
            }
        }
    }
}
