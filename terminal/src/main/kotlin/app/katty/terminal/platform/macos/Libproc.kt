package app.katty.terminal.platform.macos

import app.katty.terminal.platform.posix.libc
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// The functions of macOS's libproc that more than one file calls: one handle for each.

/** `int proc_pidinfo(int pid, int flavor, uint64_t arg, void *buffer, int buffersize)`. */
internal val procPidInfo: MethodHandle by lazy {
    libc(
        "proc_pidinfo",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ),
    )
}

/** `int proc_listpids(uint32_t type, uint32_t typeinfo, void *buffer, int buffersize)`. */
internal val procListPids: MethodHandle by lazy {
    libc(
        "proc_listpids",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
}
