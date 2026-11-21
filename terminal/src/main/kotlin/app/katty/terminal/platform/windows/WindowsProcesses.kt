package app.katty.terminal.platform.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

// What Windows says of its processes, through FFM on kernel32, ntdll and shell32: the system's list of processes with their parents (which the
// JDK's ProcessHandle hides once a parent has ended), the command line of a process (which ProcessHandle does not read on Windows: measured),
// and whether an executable is a graphical program.

private val linker = Linker.nativeLinker()

private fun function(library: String, name: String, descriptor: FunctionDescriptor): MethodHandle {
    val lookup = SymbolLookup.libraryLookup(library, Arena.global())
    return linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("$name is not in $library") }, descriptor)
}

private val ADDRESS = ValueLayout.ADDRESS
private val INT = ValueLayout.JAVA_INT

// `HANDLE CreateToolhelp32Snapshot(DWORD dwFlags, DWORD th32ProcessID)`
private val createSnapshot by lazy { function("kernel32.dll", "CreateToolhelp32Snapshot", FunctionDescriptor.of(ADDRESS, INT, INT)) }

// `BOOL Process32FirstW(HANDLE, LPPROCESSENTRY32W)`, `BOOL Process32NextW(HANDLE, LPPROCESSENTRY32W)`
private val processFirst by lazy { function("kernel32.dll", "Process32FirstW", FunctionDescriptor.of(INT, ADDRESS, ADDRESS)) }
private val processNext by lazy { function("kernel32.dll", "Process32NextW", FunctionDescriptor.of(INT, ADDRESS, ADDRESS)) }

// `BOOL CloseHandle(HANDLE)`, `HANDLE OpenProcess(DWORD dwDesiredAccess, BOOL bInheritHandle, DWORD dwProcessId)`, `HLOCAL LocalFree(HLOCAL)`
private val closeHandle by lazy { function("kernel32.dll", "CloseHandle", FunctionDescriptor.of(INT, ADDRESS)) }
private val openProcess by lazy { function("kernel32.dll", "OpenProcess", FunctionDescriptor.of(ADDRESS, INT, INT, INT)) }
private val localFree by lazy { function("kernel32.dll", "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS)) }

// `NTSTATUS NtQueryInformationProcess(HANDLE, PROCESSINFOCLASS, PVOID, ULONG, PULONG)`
private val queryInformationProcess by lazy {
    function("ntdll.dll", "NtQueryInformationProcess", FunctionDescriptor.of(INT, ADDRESS, INT, ADDRESS, INT, ADDRESS))
}

// `LPWSTR *CommandLineToArgvW(LPCWSTR lpCmdLine, int *pNumArgs)`
private val commandLineToArgv by lazy { function("shell32.dll", "CommandLineToArgvW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)) }

private const val TH32CS_SNAPPROCESS = 0x2
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
private const val PROCESS_COMMAND_LINE_INFORMATION = 60
private const val STATUS_INFO_LENGTH_MISMATCH = 0xC0000004.toInt()

// `PROCESSENTRY32W` of a 64-bit Windows (ARM64 and x64): dwSize, cntUsage, th32ProcessID, th32DefaultHeapID (ULONG_PTR, at 16),
// th32ModuleID, cntThreads, th32ParentProcessID (at 32), pcPriClassBase, dwFlags, szExeFile (WCHAR[260], at 44), 568 bytes in all.
private const val ENTRY_SIZE = 568L
private const val ENTRY_PID = 8L
private const val ENTRY_PARENT = 32L
private const val ENTRY_EXE = 44L

/** A process as the system lists it: its [pid], the pid of the process that started it ([parent], which may have ended), and its file's name. */
internal data class WindowsProcess(val pid: Int, val parent: Int, val exeName: String)

/** Every process of the system, as one snapshot of it; empty when it cannot be taken. */
internal fun processSnapshot(): List<WindowsProcess> = Arena.ofConfined().use { arena ->
    val snapshot = createSnapshot.invokeExact(TH32CS_SNAPPROCESS, 0) as MemorySegment
    if (snapshot.address() == -1L || snapshot.address() == 0L) return emptyList()
    try {
        val entry = arena.allocate(ENTRY_SIZE, 8)
        entry.set(INT, 0, ENTRY_SIZE.toInt())
        val processes = ArrayList<WindowsProcess>()
        var more = processFirst.invokeExact(snapshot, entry) as Int != 0
        while (more) {
            processes += WindowsProcess(entry.get(INT, ENTRY_PID), entry.get(INT, ENTRY_PARENT), entry.getString(ENTRY_EXE, StandardCharsets.UTF_16LE))
            more = processNext.invokeExact(snapshot, entry) as Int != 0
        }
        processes
    } finally {
        closeHandle.invokeExact(snapshot) as Int
    }
}

/** The command line of [pid] as Windows has it, split as programs split theirs (`CommandLineToArgvW`); `null` when it cannot be read. */
internal fun commandLine(pid: Int): List<String>? = Arena.ofConfined().use { arena ->
    val process = openProcess.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) as MemorySegment
    if (process.address() == 0L) return null
    try {
        val length = arena.allocate(INT)
        // A first call tells how big the answer is: a UNICODE_STRING (Length, MaximumLength, Buffer) followed by the text it points to.
        var buffer = arena.allocate(512, 8)
        var status = queryInformationProcess.invokeExact(process, PROCESS_COMMAND_LINE_INFORMATION, buffer, buffer.byteSize().toInt(), length) as Int
        if (status == STATUS_INFO_LENGTH_MISMATCH) {
            buffer = arena.allocate(length.get(INT, 0).toLong(), 8)
            status = queryInformationProcess.invokeExact(process, PROCESS_COMMAND_LINE_INFORMATION, buffer, buffer.byteSize().toInt(), length) as Int
        }
        if (status != 0) return null
        val bytes = java.lang.Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT, 0)).toLong()
        val text = buffer.get(ADDRESS, 8).reinterpret(bytes)
        val line = String(text.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE)
        splitCommandLine(arena, line)
    } finally {
        closeHandle.invokeExact(process) as Int
    }
}

// The words of a command line of Windows, as `CommandLineToArgvW` splits it (the rule of the C runtime, which most programs follow).
private fun splitCommandLine(arena: Arena, line: String): List<String>? {
    if (line.isBlank()) return null
    val count = arena.allocate(INT)
    val words = commandLineToArgv.invokeExact(arena.allocateFrom(line, StandardCharsets.UTF_16LE), count) as MemorySegment
    if (words.address() == 0L) return null
    try {
        val size = count.get(INT, 0)
        val array = words.reinterpret(size * ADDRESS.byteSize())
        return (0 until size).map { array.getAtIndex(ADDRESS, it.toLong()).reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE) }
    } finally {
        localFree.invokeExact(words) as MemorySegment
    }
}

// Whether an executable is a graphical program, by its path: read once.
private val graphical = ConcurrentHashMap<String, Boolean>()

/**
 * Whether the executable at [path] is a graphical program of Windows (the `Subsystem` of its PE header is `WINDOWS_GUI`): a shell does not wait
 * for one (`notepad`), which stays its child while the shell is back at its prompt. `false` when the file cannot be read.
 */
internal fun isGraphicalProgram(path: String): Boolean = graphical.computeIfAbsent(path) { subsystem(Path.of(it)) == IMAGE_SUBSYSTEM_WINDOWS_GUI }

private const val IMAGE_SUBSYSTEM_WINDOWS_GUI = 2

// The `Subsystem` of the optional header of a PE file, `null` when it is not one: `e_lfanew` (at 0x3C) is where `PE\0\0` is, followed by the COFF
// header (20 bytes) and the optional header, whose `Subsystem` is at 68 in both PE32 and PE32+.
internal fun subsystem(file: Path): Int? = try {
    FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        fun read(at: Long, size: Int): ByteBuffer? {
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) if (channel.read(buffer, at + buffer.position()) < 0) return null
            return buffer.flip()
        }
        val dos = read(0, 64) ?: return null
        if (dos.getShort(0) != 0x5A4D.toShort()) return null
        val header = dos.getInt(0x3C).toLong()
        val pe = read(header, 24 + 70) ?: return null
        if (pe.getInt(0) != 0x00004550) return null
        java.lang.Short.toUnsignedInt(pe.getShort(24 + 68))
    }
} catch (_: Exception) {
    null
}
