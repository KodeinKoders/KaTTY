package app.katty.update

import app.katty.core.ManagedUpdates
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

private val logger = LoggerFactory.getLogger("app.katty.update.ManagedPreferences")

/** The preferences domain of the application, which a configuration profile names (`PayloadType`). */
internal const val PREFERENCES_DOMAIN = "net.kodein.app.katty"

private const val UTF8 = 0x08000100

/** A preference as macOS has it: its value (a `Boolean` or a `String`, `null` for none or another type), and whether it is imposed. */
internal data class Preference(val value: Any?, val forced: Boolean)

/**
 * What the organization imposes on the updates: only a preference that is **forced** counts, which is one that a configuration profile (MDM) sets.
 * One that the user wrote with `defaults write` is not: it would be a setting that the settings do not show.
 */
internal fun managedUpdates(read: (String) -> Preference?): ManagedUpdates {
    fun forced(key: String): Any? = read(key)?.takeIf { it.forced }?.value
    return ManagedUpdates(
        disabled = forced("UpdatesDisabled") as? Boolean,
        check = ManagedUpdates.checkNamed(forced("UpdateCheck") as? String),
        action = ManagedUpdates.actionNamed(forced("UpdateAction") as? String),
        feed = (forced("UpdateFeedURL") as? String)?.takeIf { it.isNotBlank() },
    )
}

/** The managed preferences of the application's domain, read again at each call (a profile can be installed while it runs). Nothing off macOS. */
internal fun readManagedUpdates(): ManagedUpdates =
    if (!System.getProperty("os.name").startsWith("Mac")) ManagedUpdates.NONE else managedUpdates { key -> readPreference(PREFERENCES_DOMAIN, key) }

/**
 * A preference of [domain], through CoreFoundation's `CFPreferencesCopyAppValue` and `CFPreferencesAppValueIsForced`, by FFM. A failure is no
 * preference: the updates then follow the user's settings.
 */
internal fun readPreference(domain: String, key: String): Preference? = try {
    Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena)
        fun handle(name: String, descriptor: FunctionDescriptor) =
            linker.downcallHandle(cf.find(name).orElseThrow { IllegalStateException("$name is not found") }, descriptor)

        val createString = handle("CFStringCreateWithCString", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
        val release = handle("CFRelease", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        val copyValue = handle("CFPreferencesCopyAppValue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        val isForced = handle("CFPreferencesAppValueIsForced", FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        val typeOf = handle("CFGetTypeID", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
        val stringType = handle("CFStringGetTypeID", FunctionDescriptor.of(ValueLayout.JAVA_LONG))
        val booleanType = handle("CFBooleanGetTypeID", FunctionDescriptor.of(ValueLayout.JAVA_LONG))
        val booleanValue = handle("CFBooleanGetValue", FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS))
        val stringValue = handle("CFStringGetCString", FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT))

        val cfKey = createString.invoke(MemorySegment.NULL, arena.allocateFrom(key), UTF8) as MemorySegment
        val cfDomain = createString.invoke(MemorySegment.NULL, arena.allocateFrom(domain), UTF8) as MemorySegment
        try {
            val value = copyValue.invoke(cfKey, cfDomain) as MemorySegment
            if (value == MemorySegment.NULL) return@use null
            try {
                val type = typeOf.invoke(value) as Long
                val read: Any? = when (type) {
                    booleanType.invoke() as Long -> (booleanValue.invoke(value) as Byte).toInt() != 0
                    stringType.invoke() as Long -> {
                        val buffer = arena.allocate(4096)
                        if ((stringValue.invoke(value, buffer, 4096L, UTF8) as Byte).toInt() != 0) buffer.getString(0) else null
                    }
                    else -> null
                }
                Preference(read, (isForced.invoke(cfKey, cfDomain) as Byte).toInt() != 0)
            } finally {
                release.invoke(value)
            }
        } finally {
            release.invoke(cfKey)
            release.invoke(cfDomain)
        }
    }
} catch (e: Throwable) {
    logger.debug("Could not read the preference {} of {}", key, domain, e)
    null
}
