package app.katty.terminal

private const val ESC: Byte = 0x1B
private const val BRACKET: Byte = '['.code.toByte()
private const val QUESTION: Byte = '?'.code.toByte()
private const val SEMICOLON: Byte = ';'.code.toByte()
private const val SET: Byte = 'h'.code.toByte()
private const val RESET: Byte = 'l'.code.toByte()

// A mode sequence is a few bytes: this is more than any that is sent, and bounds what is held back between two reads.
private const val MAX_SEQUENCE = 32

/**
 * Removes DEC mode 12 (the blinking of the cursor) from what a program writes, before the terminal reads it.
 *
 * Programs use it to say that their cursor does not blink: Emacs does, with the terminfo capability `cnorm` (`ESC [ ? 12 l`
 * and `ESC [ ? 25 h`), when it shows its cursor, and never sets it back, so that the cursor of the shell that follows is steady for
 * good. Ghostty ignores the mode when the blink is configured, so that a program cannot do that, and DECSCUSR (`CSI Ps SP q`) is
 * still the way to ask for a steady or a blinking cursor. libghostty-vt does not ignore it, and has no way to be told to: this
 * does, in the stream.
 *
 * It looks for `ESC [ ? Ps ; Ps ... h` and `l`, which set and reset DEC private modes, and takes the parameter 12 out of the list:
 * `?12l` and `?12h` go, `?12;25h` becomes `?25h`. Anything else is passed on as it was, byte for byte: the queries of a mode (`ESC [ ?
 * 12 $ p`, which the terminal still answers), the other modes, the other sequences and the text. Only a sequence of at most
 * [MAX_SEQUENCE] bytes is looked at, whichever way it is split.
 *
 * What is read comes in pieces, and a sequence may be cut anywhere: the end of a piece that may be the start of one is held back until
 * the next piece shows what it is. The terminal's own parser holds an incomplete sequence as well, so nothing that would show is
 * late. What is held back is lost if the program ends there, which is of no consequence either.
 *
 * Not thread-safe: the reader of the program's output is the only one that uses it.
 */
internal class CursorBlinkModeFilter {
    /** What [filter] wrote: the array that the terminal is given, which is valid until the next call. */
    var output: ByteArray = ByteArray(0)
        private set

    private var joined = ByteArray(0)
    private var scratch = ByteArray(0)
    private val held = ByteArray(MAX_SEQUENCE)
    private var heldLength = 0

    /**
     * Filters the first [length] bytes of [buffer], and returns how many bytes of [output] are the result. [output] is [buffer] itself
     * when nothing was taken out and nothing is held, which is almost always.
     */
    fun filter(buffer: ByteArray, length: Int): Int {
        if (heldLength == 0 && !contains(buffer, length)) {
            output = buffer
            return length
        }

        // What was held back, and the new bytes after it.
        val input: ByteArray
        val size: Int
        if (heldLength == 0) {
            input = buffer
            size = length
        } else {
            size = heldLength + length
            if (joined.size < size) joined = ByteArray(size)
            held.copyInto(joined, 0, 0, heldLength)
            buffer.copyInto(joined, heldLength, 0, length)
            input = joined
            heldLength = 0
        }
        if (scratch.size < size) scratch = ByteArray(size)

        var read = 0
        var written = 0
        var changed = false
        while (read < size) {
            val byte = input[read]
            if (byte != ESC) {
                scratch[written++] = byte
                read++
                continue
            }
            val end = sequenceEnd(input, read, size)
            when {
                // Not what is looked for: the escape goes through, and what follows is looked at in turn.
                end == NOT_A_SEQUENCE -> scratch[written++] = ESC.also { read++ }

                // The piece ends in what may be the start of one: it is held back.
                end == INCOMPLETE -> {
                    input.copyInto(held, 0, read, size)
                    heldLength = size - read
                    read = size
                    changed = true
                }

                else -> {
                    val before = written
                    written = rewrite(input, read, end, scratch, written)
                    if (written - before != end - read || !sameBytes(input, read, scratch, before, end - read)) changed = true
                    read = end
                }
            }
        }

        if (!changed && input === buffer) {
            output = buffer
            return length
        }
        output = scratch
        return written
    }

    private fun contains(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i] == ESC) return true
        return false
    }

    private fun sameBytes(a: ByteArray, from: Int, b: ByteArray, at: Int, count: Int): Boolean {
        for (i in 0 until count) if (a[from + i] != b[at + i]) return false
        return true
    }

    /**
     * Where the sequence that starts at [start] (an escape) ends, past its last byte; [NOT_A_SEQUENCE] if it is not a mode sequence
     * of at most [MAX_SEQUENCE] bytes; or [INCOMPLETE] if [size] is reached while it still may be one.
     */
    private fun sequenceEnd(input: ByteArray, start: Int, size: Int): Int {
        var i = start + 1
        if (i >= size) return INCOMPLETE
        if (input[i] != BRACKET) return NOT_A_SEQUENCE
        i++
        if (i >= size) return INCOMPLETE
        if (input[i] != QUESTION) return NOT_A_SEQUENCE
        i++
        while (true) {
            if (i - start >= MAX_SEQUENCE) return NOT_A_SEQUENCE
            if (i >= size) return INCOMPLETE
            val byte = input[i]
            when {
                byte in '0'.code.toByte()..'9'.code.toByte() || byte == SEMICOLON -> i++
                byte == SET || byte == RESET -> return i + 1
                else -> return NOT_A_SEQUENCE
            }
        }
    }

    /**
     * Writes the sequence at [start] until [end] to [target] at [at], without the parameter 12, and returns where it ends: where it was,
     * if it did not have it. Nothing at all if it had no other.
     */
    private fun rewrite(input: ByteArray, start: Int, end: Int, target: ByteArray, at: Int): Int {
        val parametersStart = start + 3
        val parametersEnd = end - 1
        // The parameters, which are digits between semicolons.
        val kept = ArrayList<IntRange>()
        var removed = false
        var tokenStart = parametersStart
        for (i in parametersStart..parametersEnd) {
            if (i == parametersEnd || input[i] == SEMICOLON) {
                if (isTwelve(input, tokenStart, i)) removed = true else kept += tokenStart until i
                tokenStart = i + 1
            }
        }
        if (!removed) {
            input.copyInto(target, at, start, end)
            return at + (end - start)
        }
        if (kept.isEmpty()) return at

        var out = at
        target[out++] = ESC
        target[out++] = BRACKET
        target[out++] = QUESTION
        for ((n, range) in kept.withIndex()) {
            if (n > 0) target[out++] = SEMICOLON
            for (i in range) target[out++] = input[i]
        }
        target[out++] = input[end - 1]
        return out
    }

    // 12, with or without zeros before it.
    private fun isTwelve(input: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        while (i < to && input[i] == '0'.code.toByte()) i++
        return to - i == 2 && input[i] == '1'.code.toByte() && input[i + 1] == '2'.code.toByte()
    }

    private companion object {
        const val NOT_A_SEQUENCE = -1
        const val INCOMPLETE = -2
    }
}
