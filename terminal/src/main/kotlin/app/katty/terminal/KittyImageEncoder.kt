package app.katty.terminal

import java.util.Base64

/**
 * Writes an image as the Kitty graphics protocol says it: a transmission and a display at the cursor (`a=T`) of raw RGBA pixels (`f=32`),
 * in chunks of base64 (4096 characters at most), which libghostty places, scrolls and deletes like the images of the programs that speak
 * the protocol. It is what Sixel and iTerm2 images are turned into.
 *
 * `q=2` turns the terminal's answers off, which would be the program's input. There is no image id: it cannot be the one of an image that
 * the program has of its own, and nothing refers to it.
 */
internal object KittyImageEncoder {
    private const val RAW_CHUNK = 3072

    /**
     * Sends the image to [sink], which is given the bytes in order.
     *
     * @param columns How many cells wide to show it, or `null` for as many as its pixels take; the height follows when it is `null` too.
     * @param rows How many cells high.
     * @param keepCursor Whether the cursor stays where it is: it moves after the image otherwise.
     */
    fun encode(image: DecodedImage, columns: Int?, rows: Int?, keepCursor: Boolean, sink: (ByteArray, Int, Int) -> Unit) {
        val encoder = Base64.getEncoder()
        val raw = image.rgba
        var offset = 0
        var first = true
        while (offset < raw.size || first) {
            val count = minOf(RAW_CHUNK, raw.size - offset)
            val more = offset + count < raw.size
            val header = StringBuilder("\u001b_G")
            if (first) {
                header.append("a=T,f=32,s=").append(image.width).append(",v=").append(image.height)
                if (columns != null) header.append(",c=").append(columns)
                if (rows != null) header.append(",r=").append(rows)
                if (keepCursor) header.append(",C=1")
                header.append(",q=2,m=")
            } else {
                header.append("m=")
            }
            header.append(if (more) '1' else '0').append(';')
            val headerBytes = header.toString().toByteArray(Charsets.US_ASCII)
            sink(headerBytes, 0, headerBytes.size)
            val encoded = encoder.encode(raw.copyOfRange(offset, offset + count))
            sink(encoded, 0, encoded.size)
            sink(TERMINATOR, 0, TERMINATOR.size)
            offset += count
            first = false
        }
    }

    private val TERMINATOR = byteArrayOf(0x1B, '\\'.code.toByte())
}
