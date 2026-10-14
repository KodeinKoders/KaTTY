package app.katty

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.PressAndHold")

private const val KEY = "ApplePressAndHoldEnabled"

/**
 * Tells macOS that holding a key repeats it, which it does by default only for programs that say so.
 *
 * Without this, a view that takes part in text input, as the terminal does to show a dead key while it is
 * composed, shows the menu of accented characters when a letter is held, instead of repeating it: there would
 * be no scrolling with `j` in vim. Ghostty does the same.
 *
 * The setting is registered for this process only, as a default: a value that the user set themselves wins.
 * It has to be done before the first key is typed, so at the start of the program. Nothing happens off macOS,
 * and a failure is logged: the application works without it.
 */
internal fun disablePressAndHold() {
    if (!System.getProperty("os.name").startsWith("Mac")) return
    try {
        Objc().use { it.registerDefault(KEY, false) }
        logger.debug("{} is registered as false", KEY)
    } catch (e: Throwable) {
        logger.warn("Could not register {}: holding a key may show accents instead of repeating it", KEY, e)
    }
}

/**
 * The little of the Objective-C runtime that it takes: messages to objects, through `objc_msgSend`.
 *
 * Every call is in an autorelease pool, which a thread that is not AppKit's does not have.
 */
internal class Objc : AutoCloseable {
    private val arena = Arena.ofConfined()
    private val linker = Linker.nativeLinker()

    private val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena)

    init {
        // The classes of Foundation are only known once it is loaded.
        SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena)
    }

    private fun handle(name: String, result: java.lang.foreign.MemoryLayout?, vararg arguments: java.lang.foreign.MemoryLayout): MethodHandle {
        val symbol = objc.find(name).orElseThrow { IllegalStateException("$name is not in libobjc") }
        val descriptor = if (result == null) FunctionDescriptor.ofVoid(*arguments) else FunctionDescriptor.of(result, *arguments)
        return linker.downcallHandle(symbol, descriptor)
    }

    private val getClass = handle("objc_getClass", ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    private val registerName = handle("sel_registerName", ValueLayout.ADDRESS, ValueLayout.ADDRESS)

    // objc_msgSend, with the signature of each message: the object, the selector, and the arguments.
    private val send0 = handle("objc_msgSend", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    private val send1 = handle("objc_msgSend", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    private val send2 = handle("objc_msgSend", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    private val sendBool = handle("objc_msgSend", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
    private val returnsBool = handle("objc_msgSend", ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

    private val pool: MemorySegment

    init {
        pool = send0.invoke(cls("NSAutoreleasePool"), selector("new")) as MemorySegment
    }

    private fun cls(name: String): MemorySegment = getClass.invoke(arena.allocateFrom(name)) as MemorySegment

    private fun selector(name: String): MemorySegment = registerName.invoke(arena.allocateFrom(name)) as MemorySegment

    private fun string(value: String): MemorySegment =
        send1.invoke(cls("NSString"), selector("stringWithUTF8String:"), arena.allocateFrom(value)) as MemorySegment

    /** `[[NSUserDefaults standardUserDefaults] registerDefaults:@{ key: @(value) }]`. */
    fun registerDefault(key: String, value: Boolean) {
        val number = sendBool.invoke(cls("NSNumber"), selector("numberWithBool:"), value) as MemorySegment
        val dictionary = send2.invoke(cls("NSDictionary"), selector("dictionaryWithObject:forKey:"), number, string(key)) as MemorySegment
        val defaults = send0.invoke(cls("NSUserDefaults"), selector("standardUserDefaults")) as MemorySegment
        send1.invoke(defaults, selector("registerDefaults:"), dictionary)
    }

    /**
     * What was registered as the default of [key], whatever the user set, or `null` if nothing: for the tests.
     */
    fun registeredDefault(key: String): Boolean? {
        val defaults = send0.invoke(cls("NSUserDefaults"), selector("standardUserDefaults")) as MemorySegment
        val registered = send1.invoke(defaults, selector("volatileDomainForName:"), string("NSRegistrationDomain")) as MemorySegment
        if (registered == MemorySegment.NULL) return null
        val number = send1.invoke(registered, selector("objectForKey:"), string(key)) as MemorySegment
        if (number == MemorySegment.NULL) return null
        return returnsBool.invoke(number, selector("boolValue")) as Boolean
    }

    override fun close() {
        try {
            send0.invoke(pool, selector("drain"))
        } finally {
            arena.close()
        }
    }
}
