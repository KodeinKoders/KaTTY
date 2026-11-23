package app.katty.ghostty

/**
 * Grows the stack of a thread on Windows before it first writes to a terminal, the normal way: a page after the other, from Java.
 *
 * A write to a terminal calls back into Java (the effects of [GhosttyTerminal], the PNG decoder), and what the JVM does in a callback (loading a
 * class, touching the pages below its stack pointer) may need more stack than the thread has used so far. On Windows a thread's stack is reserved
 * and committed as it grows, through a guard page; when it had to grow during a callback from libghostty, Windows committed the pages without
 * moving the guard page nor the limit of the stack, and the next page below was only reserved: the JVM crashed with an access violation on its own
 * stack (measured on ARM64 and x64, in the thread that reads a terminal's output, with minidumps: the pages below the limit of the thread were
 * committed, and none below them was a guard page). Why the growth goes wrong in a callback was not found. A stack that has already grown does
 * not need to in a callback: with this done first, the crash did not happen again.
 */
internal object StackGrowth {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    // Whether the stack of the current thread has been grown.
    private val grown = ThreadLocal.withInitial { false }

    // How deep the recursion goes: about 400 kB of the 1 MB stack of a Java thread while it is interpreted, less once compiled (measured: enough
    // for the whole of the terminal's tests, which start hundreds of sessions).
    private const val DEPTH = 4000

    /** Grows the stack of the current thread on Windows, once for each thread; nothing elsewhere. */
    fun ensure() {
        if (!windows || grown.get()) return
        try {
            grow(DEPTH)
        } catch (_: StackOverflowError) {
            // A thread that was already deep: its stack has grown to its end, which is all there was to do.
        }
        grown.set(true)
    }

    // A frame on the stack for each level; the result is used so that the calls are not taken away.
    private fun grow(depth: Int): Long {
        val here = depth.toLong() * 3
        return if (depth == 0) 0 else grow(depth - 1) + here
    }
}
