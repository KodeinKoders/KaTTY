package app.katty.terminal

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OutputTranslator::class.java)

private const val ESC: Byte = 0x1B
private const val BEL: Byte = 0x07
private const val CAN: Byte = 0x18
private const val SUB: Byte = 0x1A
private const val BACKSLASH: Byte = '\\'.code.toByte()
private const val SEMICOLON: Byte = ';'.code.toByte()
private const val QUESTION: Byte = '?'.code.toByte()
private const val SPACE: Byte = ' '.code.toByte()

// A mode sequence is a few bytes: this is more than any that is sent, and bounds what is held back between two reads.
private const val MAX_SEQUENCE = 32

// The start of an OSC or a DCS that decides what it is: the number and what follows it, or the parameters up to the final byte.
private const val MAX_HEAD = 32

// How big a string that is translated (an image) may be: past it, it is dropped.
private const val MAX_PAYLOAD = 32 * 1024 * 1024

// How much output buffer is kept from a piece to the next.
private const val RETAINED_OUTPUT = 4 * 1024 * 1024

/** How big a cell is and how many there are, for what has to be sized in cells: the image of a program that gives a size in cells or percent. */
internal data class CellGeometry(val cellWidthPx: Int, val cellHeightPx: Int, val cols: Int, val rows: Int)

/**
 * What a program writes, read on its way to the terminal, which libghostty-vt cannot be told to do and does not do itself.
 *
 * Everything is passed on as it was, byte for byte, **except**:
 * - **DEC mode 12** (the blinking of the cursor) is taken out of the sets and resets of private modes: `?12l` and `?12h` go, `?12;25h`
 *   becomes `?25h`. Emacs shows its cursor with the terminfo capability `cnorm` (`ESC [ ? 12 l ESC [ ? 25 h`) and never sets the mode
 *   back, so the cursor of the shell that follows would be steady for good. Ghostty ignores the mode when the blink is configured, and
 *   DECSCUSR (`CSI Ps SP q`) is still the way to ask for a steady or a blinking cursor. A query of the mode (`ESC [ ? 12 $ p`) is
 *   still answered by the terminal.
 * - **The cursor of the shell is given back when a command ends.** The shell's marks (OSC 133) say where a command starts (`C`) and where
 *   the next prompt does (`A`): the style (DECSCUSR) and the visibility that the cursor had at `C` are put back, before `A`, if the
 *   command changed them. It only works for a shell that sends the marks. The reports of the focus (mode 1004) that a command turned on
 *   and did not turn off are turned off then too: a shell does not read them, and takes them for keys that it rings the bell at.
 *
 * It follows the structure of the stream (escape sequences, strings up to BEL or ST), and a string that it does not translate is passed
 * on as it comes, the images of the Kitty protocol included: what is in it is never taken for a sequence.
 *
 * What is read comes in pieces, and a sequence may be cut anywhere: the end of a piece that may be the start of one is **held back**
 * until the next piece shows what it is, and the result is the same however the stream is cut. The terminal's own parser holds an
 * incomplete sequence as well, so nothing that would show is late. What is held back is lost if the program ends there.
 *
 * Not thread-safe: the reader of the program's output is the only one that uses it.
 */
internal class OutputTranslator(
    private val maxPayload: Int = MAX_PAYLOAD,
    private val geometry: () -> CellGeometry = { CellGeometry(8, 16, 80, 24) },
) {
    /** What [filter] wrote: the array that the terminal is given, which is valid until the next call. */
    var output: ByteArray = ByteArray(0)
        private set

    private enum class State {
        GROUND, ESC, CSI,
        OSC_HEAD, OSC_PASS, OSC_PASS_ESC, OSC_HOLD, OSC_HOLD_ESC,
        DCS_HEAD, DCS_PASS, DCS_PASS_ESC, DCS_HOLD, DCS_HOLD_ESC,
        STRING_PASS, STRING_PASS_ESC,
    }

    private var state = State.GROUND

    // What is held back: the start of a sequence that is not decided, or a string that is translated.
    private val held = GrowableBytes(MAX_HEAD)
    private val payload = GrowableBytes(0)
    private var dropping = false

    /** How many bytes of a string that is translated are held: for the tests of its limit. */
    internal val heldBytes: Int get() = payload.size

    // What is written.
    private var out = ByteArray(8 * 1024)
    private var written = 0
    private var modified = false

    // What the cursor is now, and what the shell had when the command started: see the class.
    private var cursorStyle = 0
    private var cursorVisible = true
    private var shellStyle = 0
    private var shellVisible = true
    private var commandRunning = false

    // Whether focus reporting (mode 1004) is on, and whether the shell had it on when the command started.
    private var focusEvents = false
    private var shellFocusEvents = false

    /**
     * Translates the first [length] bytes of [buffer], and returns how many bytes of [output] are the result. [output] is [buffer] itself
     * when nothing changed and nothing is held, which is almost always.
     */
    fun filter(buffer: ByteArray, length: Int): Int {
        if (state == State.GROUND && !contains(buffer, length)) {
            output = buffer
            return length
        }
        written = 0
        // What an image needed is not kept.
        if (out.size > RETAINED_OUTPUT) out = ByteArray(8 * 1024)
        // What was held back before is not in this piece.
        modified = held.size > 0 || payload.size > 0
        ensure(length + held.size)

        var i = 0
        while (i < length) {
            i = when (state) {
                State.GROUND -> ground(buffer, i, length)
                State.OSC_PASS, State.DCS_PASS, State.STRING_PASS -> passString(buffer, i, length)
                State.OSC_HOLD, State.DCS_HOLD -> holdString(buffer, i, length)
                else -> {
                    byteAt(buffer[i])
                    i + 1
                }
            }
        }

        // Something is still held back: it is not given yet.
        if (held.size > 0 || payload.size > 0) modified = true
        if (!modified && written == length) {
            output = buffer
            return length
        }
        output = out
        return written
    }

    private fun contains(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i] == ESC) return true
        return false
    }

    // Plain text, up to the next escape.
    private fun ground(buffer: ByteArray, start: Int, length: Int): Int {
        var end = start
        while (end < length && buffer[end] != ESC) end++
        emit(buffer, start, end)
        if (end < length) {
            held.clear()
            held.add(ESC)
            state = State.ESC
            return end + 1
        }
        return end
    }

    // A string that is passed on: up to its terminator, as it comes.
    private fun passString(buffer: ByteArray, start: Int, length: Int): Int {
        var end = start
        while (end < length) {
            val b = buffer[end]
            if (b == BEL || b == ESC || b == CAN || b == SUB) break
            end++
        }
        emit(buffer, start, end)
        if (end < length) {
            byteAt(buffer[end])
            return end + 1
        }
        return end
    }

    // A string that is translated: kept whole, up to its terminator.
    private fun holdString(buffer: ByteArray, start: Int, length: Int): Int {
        var end = start
        while (end < length) {
            val b = buffer[end]
            if (b == BEL || b == ESC || b == CAN || b == SUB) break
            end++
        }
        hold(buffer, start, end)
        modified = true
        if (end < length) {
            byteAt(buffer[end])
            return end + 1
        }
        return end
    }

    // Adds what is between [start] and [end] to the string that is kept, up to its limit: past it the string is dropped, and nothing more is kept
    // of it up to its end.
    private fun hold(buffer: ByteArray, start: Int, end: Int) {
        if (dropping) return
        if (payload.size + (end - start) > maxPayload) {
            logger.warn("An image of more than {} bytes is dropped", maxPayload)
            dropping = true
            payload.clear()
        } else {
            payload.add(buffer, start, end)
        }
    }

    // One byte, in the state of a sequence that is being read.
    private fun byteAt(b: Byte) {
        when (state) {
            State.GROUND -> {
                if (b == ESC) {
                    held.clear()
                    held.add(ESC)
                    state = State.ESC
                } else {
                    emit(b)
                }
            }

            State.ESC -> afterEscape(b)
            State.CSI -> inCsi(b)
            State.OSC_HEAD -> inOscHead(b)
            State.DCS_HEAD -> inDcsHead(b)

            State.OSC_PASS -> when (b) {
                BEL, CAN, SUB -> { emit(b); state = State.GROUND }
                ESC -> { held.clear(); held.add(ESC); state = State.OSC_PASS_ESC }
                else -> emit(b)
            }
            State.DCS_PASS -> when (b) {
                CAN, SUB -> { emit(b); state = State.GROUND }
                ESC -> { held.clear(); held.add(ESC); state = State.DCS_PASS_ESC }
                else -> emit(b)
            }
            State.STRING_PASS -> when (b) {
                BEL, CAN, SUB -> { emit(b); state = State.GROUND }
                ESC -> { held.clear(); held.add(ESC); state = State.STRING_PASS_ESC }
                else -> emit(b)
            }
            State.OSC_PASS_ESC, State.DCS_PASS_ESC, State.STRING_PASS_ESC -> {
                if (b == BACKSLASH) {
                    emitHeld()
                    emit(b)
                    state = State.GROUND
                } else {
                    // The escape ended the string: it starts a sequence, and this is its second byte.
                    state = State.ESC
                    afterEscape(b)
                }
            }

            State.OSC_HOLD -> when (b) {
                BEL -> { finishString(osc = true, terminator = byteArrayOf(b)); }
                CAN, SUB -> { discardPayload(); emit(b); state = State.GROUND }
                ESC -> { state = State.OSC_HOLD_ESC }
                else -> Unit
            }
            State.DCS_HOLD -> when (b) {
                CAN, SUB -> { discardPayload(); emit(b); state = State.GROUND }
                ESC -> { state = State.DCS_HOLD_ESC }
                // A BEL, which does not end a DCS: one more byte of it, which counts towards its limit.
                else -> hold(byteArrayOf(b), 0, 1)
            }
            State.OSC_HOLD_ESC, State.DCS_HOLD_ESC -> {
                if (b == BACKSLASH) {
                    finishString(osc = state == State.OSC_HOLD_ESC, terminator = byteArrayOf(ESC, b))
                } else {
                    // The escape ended the string, which was cut short: what it held is given up, and the escape is read.
                    discardPayload()
                    held.clear()
                    held.add(ESC)
                    state = State.ESC
                    afterEscape(b)
                }
            }
        }
    }

    // The byte after an escape, which is in [held].
    private fun afterEscape(b: Byte) {
        when (b) {
            '['.code.toByte() -> { held.add(b); state = State.CSI }
            ']'.code.toByte() -> { held.add(b); state = State.OSC_HEAD }
            'P'.code.toByte() -> { held.add(b); state = State.DCS_HEAD }
            '_'.code.toByte(), '^'.code.toByte(), 'X'.code.toByte() -> {
                emitHeld()
                emit(b)
                state = State.STRING_PASS
            }
            // An escape and another: the first is given, the second may start a sequence.
            ESC -> { emitHeld(); held.clear(); held.add(ESC) }
            else -> { emitHeld(); emit(b); state = State.GROUND }
        }
    }

    // ---- CSI

    private fun inCsi(b: Byte) {
        val code = b.toInt() and 0xFF
        when {
            // Parameters and intermediates.
            code in 0x20..0x3F && held.size < MAX_SEQUENCE - 1 -> held.add(b)
            // The final byte.
            code in 0x40..0x7E -> {
                held.add(b)
                decideCsi()
                state = State.GROUND
            }
            // Anything else is not what is looked for: what was held is given, and the byte is read again.
            else -> {
                emitHeld()
                state = State.GROUND
                byteAt(b)
            }
        }
    }

    private fun decideCsi() {
        val n = held.size
        val final = held[n - 1]
        // ESC [ ? Ps ; Ps ... (h|l): the modes.
        if (n >= 4 && held[2] == QUESTION && (final == 'h'.code.toByte() || final == 'l'.code.toByte()) && onlyDigitsAndSemicolons(3, n - 1)) {
            rewriteModes(final == 'h'.code.toByte())
            return
        }
        // ESC [ Ps SP q: DECSCUSR.
        if (final == 'q'.code.toByte() && n >= 4 && held[n - 2] == SPACE && onlyDigitsAndSemicolons(2, n - 2)) {
            cursorStyle = parseStyle(2, n - 2)
        }
        emitHeld()
    }

    private fun onlyDigitsAndSemicolons(from: Int, to: Int): Boolean {
        for (i in from until to) {
            val b = held[i]
            if (b != SEMICOLON && (b < '0'.code.toByte() || b > '9'.code.toByte())) return false
        }
        return true
    }

    private fun parseStyle(from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) {
            val b = held[i]
            if (b == SEMICOLON) break
            value = (value * 10 + (b - '0'.code.toByte())).coerceAtMost(1000)
        }
        // Only the six styles and the default mean something: anything else is ignored by the terminal and does not change what it is.
        return if (value in 0..6) value else cursorStyle
    }

    // The parameters of ESC [ ? ... h or l, without the 12: what is left is given, or nothing.
    private fun rewriteModes(set: Boolean) {
        val n = held.size
        val kept = ArrayList<IntRange>()
        var removed = false
        var tokenStart = 3
        for (i in 3 until n) {
            if (i == n - 1 || held[i] == SEMICOLON) {
                if (isNumber(tokenStart, i, 12)) removed = true else kept += tokenStart until i
                if (isNumber(tokenStart, i, 25)) cursorVisible = set
                if (isNumber(tokenStart, i, 1004)) focusEvents = set
                tokenStart = i + 1
            }
        }
        if (!removed) {
            emitHeld()
            return
        }
        modified = true
        if (kept.isEmpty()) {
            held.clear()
            return
        }
        emit(ESC)
        emit('['.code.toByte())
        emit(QUESTION)
        for ((k, range) in kept.withIndex()) {
            if (k > 0) emit(SEMICOLON)
            for (j in range) emit(held[j])
        }
        emit(held[n - 1])
        held.clear()
    }

    // The number in held[from, to), with or without zeros before it.
    private fun isNumber(from: Int, to: Int, number: Int): Boolean {
        var i = from
        while (i < to && held[i] == '0'.code.toByte()) i++
        val digits = number.toString()
        if (to - i != digits.length) return false
        for (k in digits.indices) if (held[i + k] != digits[k].code.toByte()) return false
        return true
    }

    // ---- OSC

    private fun inOscHead(b: Byte) {
        if (b == BEL || b == ESC || b == CAN || b == SUB || held.size >= MAX_HEAD) {
            // Too short to be one of ours, or too long: it is a string like another.
            emitHeld()
            state = State.OSC_PASS
            byteAt(b)
            return
        }
        held.add(b)
        // held: ESC ] then what follows.
        val head = held.size - 2
        when (oscKind(head)) {
            OscKind.MORE -> Unit
            OscKind.OTHER -> {
                // It is not one of ours: the head goes as it is, and the rest as it comes.
                emitHeld()
                state = State.OSC_PASS
            }
            OscKind.MARK -> {
                mark(held[held.size - 1])
                emitHeld()
                state = State.OSC_PASS
            }
            OscKind.ITERM_FILE -> {
                payload.clear()
                payload.add(held.bytes, 0, held.size)
                held.clear()
                dropping = false
                state = State.OSC_HOLD
            }
        }
    }

    private enum class OscKind { MORE, OTHER, MARK, ITERM_FILE }

    // What the start of an OSC (held[2, 2 + head)) says: "133;" and a letter is a mark of the shell, "1337;File=" is an image.
    private fun oscKind(head: Int): OscKind {
        val prefixes = listOf("133;", "1337;File=")
        var possible = false
        for (prefix in prefixes) {
            val common = minOf(head, prefix.length)
            var matches = true
            for (i in 0 until common) if (held[2 + i] != prefix[i].code.toByte()) { matches = false; break }
            if (!matches) continue
            possible = true
            if (prefix == "1337;File=" && head >= prefix.length) return OscKind.ITERM_FILE
            if (prefix == "133;" && head == prefix.length + 1) return OscKind.MARK
        }
        return if (possible) OscKind.MORE else OscKind.OTHER
    }

    // A mark of the shell: what the cursor was when the command started is what it is given back to when the next prompt starts.
    private fun mark(letter: Byte) {
        when (letter) {
            'C'.code.toByte() -> {
                shellStyle = cursorStyle
                shellVisible = cursorVisible
                shellFocusEvents = focusEvents
                commandRunning = true
            }
            'A'.code.toByte() -> {
                if (commandRunning) {
                    commandRunning = false
                    if (cursorStyle != shellStyle) {
                        modified = true
                        emitText("\u001b[$shellStyle q")
                        cursorStyle = shellStyle
                    }
                    // A program that was killed, or crashed, leaves the focus reports on, and the shell that follows reads them as keys it does not
                    // know and rings the bell at each change of focus.
                    if (focusEvents && !shellFocusEvents) {
                        modified = true
                        emitText("\u001b[?1004l")
                        focusEvents = false
                    }
                    if (cursorVisible != shellVisible) {
                        modified = true
                        emitText(if (shellVisible) "\u001b[?25h" else "\u001b[?25l")
                        cursorVisible = shellVisible
                    }
                }
            }
        }
    }

    // ---- DCS

    private fun inDcsHead(b: Byte) {
        val code = b.toInt() and 0xFF
        // Parameters, then intermediates, then the final byte.
        if (code in 0x30..0x3B && held.size < MAX_HEAD) {
            held.add(b)
            return
        }
        if (code == 'q'.code && onlyDigitsAndSemicolons(2, held.size)) {
            // A Sixel image: kept whole.
            held.add(b)
            payload.clear()
            payload.add(held.bytes, 0, held.size)
            held.clear()
            dropping = false
            state = State.DCS_HOLD
            return
        }
        // Another DCS (tmux, XTGETTCAP, DECRQSS): as it comes.
        emitHeld()
        state = State.DCS_PASS
        byteAt(b)
    }

    // The end of a string that was kept: what it says is turned into what the terminal reads.
    private fun finishString(osc: Boolean, terminator: ByteArray) {
        modified = true
        if (!dropping) {
            try {
                if (osc) emitIterm() else emitSixel()
            } catch (e: Exception) {
                logger.warn("An image could not be translated", e)
            } catch (e: OutOfMemoryError) {
                // What an image takes is bounded, but the memory may be short: the image is lost, not the reader.
                logger.warn("An image could not be translated", e)
            }
        }
        payload.clear()
        dropping = false
        state = State.GROUND
    }

    private fun discardPayload() {
        payload.clear()
        dropping = false
        modified = true
    }

    // A Sixel image becomes an image of the Kitty protocol, and the cursor goes to the start of the line after it, as it does for xterm.
    private fun emitSixel() {
        val image = SixelDecoder.decode(payload.bytes, 2, payload.size)
        if (image == null) {
            logger.debug("A Sixel image that could not be read was dropped")
            return
        }
        KittyImageEncoder.encode(image, null, null, false, ::emit)
        emitText("\r\n")
    }

    // An iTerm2 image becomes an image of the Kitty protocol, where the cursor goes after it.
    private fun emitIterm() {
        val picture = Iterm2Image.parse(payload.bytes, 0, payload.size, geometry())
        if (picture == null) {
            logger.debug("An iTerm2 image that is not shown was dropped")
            return
        }
        KittyImageEncoder.encode(picture.image, picture.columns, picture.rows, picture.keepCursor, ::emit)
    }

    // ---- Output

    private fun ensure(extra: Int) {
        if (written + extra > out.size) out = out.copyOf(maxOf(out.size * 2, written + extra))
    }

    private fun emit(b: Byte) {
        ensure(1)
        out[written++] = b
    }

    private fun emit(source: ByteArray, from: Int, to: Int) {
        val count = to - from
        if (count <= 0) return
        ensure(count)
        System.arraycopy(source, from, out, written, count)
        written += count
    }

    private fun emitText(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        emit(bytes, 0, bytes.size)
    }

    private fun emitHeld() {
        emit(held.bytes, 0, held.size)
        held.clear()
    }

    // A buffer of bytes that grows.
    private class GrowableBytes(initial: Int) {
        var bytes = ByteArray(maxOf(initial, 16))
            private set
        var size = 0
            private set

        operator fun get(index: Int): Byte = bytes[index]

        fun add(b: Byte) {
            if (size == bytes.size) bytes = bytes.copyOf(size * 2)
            bytes[size++] = b
        }

        fun add(source: ByteArray, from: Int, to: Int) {
            val count = to - from
            if (count <= 0) return
            if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
            System.arraycopy(source, from, bytes, size, count)
            size += count
        }

        fun clear() {
            size = 0
        }
    }
}
