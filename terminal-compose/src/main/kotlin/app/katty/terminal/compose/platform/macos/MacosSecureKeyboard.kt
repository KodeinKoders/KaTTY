package app.katty.terminal.compose.platform.macos

import app.katty.terminal.compose.platform.SecureKeyboard
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.compose.platform.macos.MacosSecureKeyboard")

/**
 * macOS's secure event input: while it is on, no other program can read what is typed (a key logger, a program that listens to the
 * keys of the system), which is what a password needs. It is the system's own, in Carbon, and counts how many times it was enabled:
 * every [enable] must be followed by a [disable]. A failure is logged.
 *
 * It also stops what listens to the keys for the user, such as a text expander or a global shortcut, so it is only on while it is needed.
 */
internal object MacosSecureKeyboard : SecureKeyboard {
    private class Calls(val enable: MethodHandle, val disable: MethodHandle, val isEnabled: MethodHandle)

    private val calls: Calls? by lazy {
        try {
            // For as long as the process lives: the handles are used until it ends.
            val lookup = SymbolLookup.libraryLookup("/System/Library/Frameworks/Carbon.framework/Carbon", Arena.global())
            val linker = Linker.nativeLinker()
            fun handle(name: String, descriptor: FunctionDescriptor) = linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)
            Calls(
                enable = handle("EnableSecureEventInput", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                disable = handle("DisableSecureEventInput", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                isEnabled = handle("IsSecureEventInputEnabled", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)),
            )
        } catch (e: Throwable) {
            logger.warn("Secure keyboard entry is not available", e)
            null
        }
    }

    override fun enable(): Boolean = call { it.enable.invoke() as Int == 0 }

    override fun disable() {
        call { it.disable.invoke(); true }
    }

    override fun isEnabled(): Boolean = call { it.isEnabled.invoke() as Boolean }

    private fun call(block: (Calls) -> Boolean): Boolean {
        val calls = calls ?: return false
        return try {
            block(calls)
        } catch (e: Throwable) {
            logger.warn("Secure keyboard entry failed", e)
            false
        }
    }
}
