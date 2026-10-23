package app.katty.terminal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private const val NAME_BYTES = 256L

// `int gethostname(char *name, size_t namelen)`.
private val gethostname: MethodHandle by lazy {
    libc("gethostname", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
}

// Asked once: it is what the shells of this machine put in the address of their directory (`$HOST`, `$HOSTNAME`, `hostname`).
private val hostName: String? by lazy {
    try {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(NAME_BYTES)
            if (gethostname.invokeExact(buffer, NAME_BYTES) as Int == 0) buffer.getString(0).takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Throwable) {
        null
    }
}

/**
 * The name of this machine, as the system has it (`gethostname`), or `null` when it cannot be known. Not `InetAddress.getLocalHost()`, which
 * asks the network and can take seconds: it is asked on the thread that reads a program's output.
 */
internal fun localHostName(): String? = hostName

/**
 * Whether [host], the host of a `file://` address, is this machine: none, `localhost`, or its name, whatever the case and with or without
 * its domain (`mac` and `mac.local`).
 */
internal fun isLocalHost(host: String, name: String? = localHostName()): Boolean {
    if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) return true
    if (name == null) return false
    return host.equals(name, ignoreCase = true) || host.substringBefore('.').equals(name.substringBefore('.'), ignoreCase = true)
}
