package app.katty.terminal

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// The functions of the C library of macOS that the terminal calls through FFM. One handle for each, which the files that call it share: a
// function that is declared twice can be declared two ways.

private val linker = Linker.nativeLinker()

/** A handle on the function [name] of the C library, which the linker finds in the process. */
internal fun libc(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
    linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

/** `int proc_pidinfo(int pid, int flavor, uint64_t arg, void *buffer, int buffersize)`, of libproc. */
internal val procPidInfo: MethodHandle by lazy {
    libc(
        "proc_pidinfo",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ),
    )
}

/** Where `errno` is in the call state of [ioctl]: the state is a segment of `Linker.Option.captureStateLayout()`. */
internal val errnoOffset: Long = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"))

/**
 * `int ioctl(int fd, unsigned long request, ...)` with a pointer as its only variadic argument (on Apple Silicon variadic arguments are not passed
 * as the fixed ones are), and `errno` captured: it is called as `ioctl(state, fd, request, pointer)`, `state` a segment of the call state layout.
 */
internal val ioctl: MethodHandle by lazy {
    libc(
        "ioctl",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        Linker.Option.firstVariadicArg(2),
        Linker.Option.captureCallState("errno"),
    )
}
