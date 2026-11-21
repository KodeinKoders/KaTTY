package app.katty.platform.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

// Reading the registry of Windows, through FFM on advapi32: the names of the keys under a key, and the text of a value.

private val ADDRESS = ValueLayout.ADDRESS
private val INT = ValueLayout.JAVA_INT

private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle {
    val lookup = SymbolLookup.libraryLookup("advapi32.dll", Arena.global())
    return Linker.nativeLinker().downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("$name is not in advapi32") }, descriptor)
}

// `LSTATUS RegOpenKeyExW(HKEY hKey, LPCWSTR lpSubKey, DWORD ulOptions, REGSAM samDesired, PHKEY phkResult)`
private val openKey by lazy { function("RegOpenKeyExW", FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT, INT, ADDRESS)) }

// `LSTATUS RegEnumKeyExW(HKEY, DWORD dwIndex, LPWSTR lpName, LPDWORD lpcchName, LPDWORD lpReserved, LPWSTR lpClass, LPDWORD lpcchClass, PFILETIME)`
private val enumKey by lazy { function("RegEnumKeyExW", FunctionDescriptor.of(INT, ADDRESS, INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }

// `LSTATUS RegGetValueW(HKEY hkey, LPCWSTR lpSubKey, LPCWSTR lpValue, DWORD dwFlags, LPDWORD pdwType, PVOID pvData, LPDWORD pcbData)`
private val getValue by lazy { function("RegGetValueW", FunctionDescriptor.of(INT, ADDRESS, ADDRESS, ADDRESS, INT, ADDRESS, ADDRESS, ADDRESS)) }

// `LSTATUS RegCloseKey(HKEY hKey)`
private val closeKey by lazy { function("RegCloseKey", FunctionDescriptor.of(INT, ADDRESS)) }

private const val ERROR_SUCCESS = 0
private const val ERROR_MORE_DATA = 234
private const val KEY_READ = 0x20019

// `RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ`: a text, with the variables of an expandable one replaced (`%ProgramFiles%`).
private const val RRF_RT_STRING = 0x00000002 or 0x00000004

/** A root of the registry: its handle is a constant, sign-extended as Windows defines it (`(HKEY)(ULONG_PTR)(LONG)0x80000002`). */
internal enum class RegistryRoot(value: Int) {
    CURRENT_USER(0x80000001.toInt()),
    LOCAL_MACHINE(0x80000002.toInt()),
    ;

    val handle: MemorySegment = MemorySegment.ofAddress(value.toLong())
}

/** The names of the keys under [path] of [root], empty when it cannot be read. */
internal fun registrySubKeys(root: RegistryRoot, path: String): List<String> = Arena.ofConfined().use { arena ->
    val key = arena.allocate(ADDRESS)
    if (openKey.invokeExact(root.handle, arena.allocateFrom(path, StandardCharsets.UTF_16LE), 0, KEY_READ, key) as Int != ERROR_SUCCESS) return emptyList()
    val opened = key.get(ADDRESS, 0)
    try {
        // A key's name has 255 characters at most.
        val name = arena.allocate(512)
        val length = arena.allocate(INT)
        val names = ArrayList<String>()
        var index = 0
        while (true) {
            length.set(INT, 0, 256)
            val status = enumKey.invokeExact(opened, index, name, length, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int
            if (status == ERROR_SUCCESS) names += name.getString(0, StandardCharsets.UTF_16LE) else if (status != ERROR_MORE_DATA) break
            index++
        }
        names
    } finally {
        closeKey.invokeExact(opened) as Int
    }
}

/** The text of the value [name] of the key [path] of [root] (`null` for its default value), `null` when there is none or it is not a text. */
internal fun registryString(root: RegistryRoot, path: String, name: String?): String? = Arena.ofConfined().use { arena ->
    val subKey = arena.allocateFrom(path, StandardCharsets.UTF_16LE)
    val value = name?.let { arena.allocateFrom(it, StandardCharsets.UTF_16LE) } ?: MemorySegment.NULL
    val size = arena.allocate(INT)
    // A first call says how big the text is, in bytes with its end.
    if (getValue.invokeExact(root.handle, subKey, value, RRF_RT_STRING, MemorySegment.NULL, MemorySegment.NULL, size) as Int != ERROR_SUCCESS) return null
    val data = arena.allocate(size.get(INT, 0).toLong().coerceAtLeast(2))
    if (getValue.invokeExact(root.handle, subKey, value, RRF_RT_STRING, MemorySegment.NULL, data, size) as Int != ERROR_SUCCESS) return null
    data.getString(0, StandardCharsets.UTF_16LE).takeIf { it.isNotEmpty() }
}
