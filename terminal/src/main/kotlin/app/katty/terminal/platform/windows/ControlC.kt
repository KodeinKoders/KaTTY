package app.katty.terminal.platform.windows

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("app.katty.terminal.platform.windows.ControlC")

// `BOOL SetConsoleCtrlHandler(PHANDLER_ROUTINE HandlerRoutine, BOOL Add)`
private val setConsoleCtrlHandler by lazy {
    val lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
    Linker.nativeLinker().downcallHandle(
        lookup.find("SetConsoleCtrlHandler").orElseThrow { IllegalStateException("SetConsoleCtrlHandler is not in kernel32") },
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
}

private val processed = AtomicBoolean(false)

/**
 * Makes this process process Control + C, once, so that the programs of its terminals do: a process inherits whether its parent ignores it, and a
 * process that a service starts (an SSH server, the agent of CI) ignores it, as one started in a new process group does. Its programs then ignored
 * the Control + C that ConPTY makes of `^C`: a `ping -t` went on (measured, in a session started over SSH and on CI; one started from the desktop
 * stopped). It only changes what this process ignores: the JVM's own handler (its shutdown on Control + C in a console it has) stays.
 */
internal fun processControlC() {
    if (!processed.compareAndSet(false, true)) return
    try {
        // A null handler and FALSE: "restores normal processing of CTRL+C input", which child processes inherit.
        if (setConsoleCtrlHandler.invokeExact(MemorySegment.NULL, 0) as Int == 0) logger.info("Control + C could not be processed: the programs of the terminals inherit what this process does")
    } catch (e: Throwable) {
        logger.warn("Control + C could not be processed", e)
    }
}
