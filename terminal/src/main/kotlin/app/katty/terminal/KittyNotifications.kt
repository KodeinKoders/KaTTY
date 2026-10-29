package app.katty.terminal

import java.io.ByteArrayOutputStream
import java.util.Base64

// How much of a notification is kept while it comes in pieces, and how many are waiting for their last piece: a program that never ends
// them must not make the session grow.
private const val MAX_NOTIFICATION_BYTES = 64 * 1024
private const val MAX_PENDING = 16

/** What the end of one OSC 99 string means. */
internal sealed interface KittyNotificationOutcome {
    /** Nothing to show: a piece that waits for the next one, or a kind that is not shown. */
    data object None : KittyNotificationOutcome

    /** A notification is complete. */
    data class Notify(val title: String, val body: String) : KittyNotificationOutcome

    /** What the terminal says back to the program (a query of what it supports). */
    data class Reply(val text: String) : KittyNotificationOutcome
}

/**
 * The desktop notifications of the Kitty protocol (`OSC 99 ; metadata ; payload ST`), which libghostty parses and does not give to the host
 * (its callback is for OSC 9 and OSC 777): the pieces of a notification are put together here, by the identifier (`i=`) that they share, up to
 * the one that says it is the last (`d=1`, or no `d`).
 *
 * What is shown is the title (`p=title`, the default) and the body (`p=body`), as text; `e=1` is base64. The urgency, the occasion
 * (`o=`: it is shown whether the window has the focus or not), the actions, the buttons, the icon and the sound are not used, and neither
 * is closing (`p=close`) or asking which are alive (`p=alive`): there is nothing to close, the host decides what it does with the event.
 * A query (`p=?`) is answered with what is understood.
 *
 * Not thread-safe: the reader of the program's output is the only one that uses it.
 */
internal class KittyNotifications {
    private class Pending {
        val title = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        val size get() = title.size() + body.size()
    }

    // In the order the pieces came: the oldest is given up first.
    private val pending = LinkedHashMap<String, Pending>()

    /** [metadata] is what is between the first and the second semicolon, [text] what is after the second, as bytes. */
    fun accept(metadata: String, text: ByteArray): KittyNotificationOutcome {
        val keys = HashMap<String, String>()
        for (pair in metadata.split(':')) {
            val equals = pair.indexOf('=')
            if (equals > 0) keys[pair.substring(0, equals)] = pair.substring(equals + 1)
        }
        // The characters that the protocol allows in an identifier: it is sent back, so nothing else is.
        val id = keys["i"]?.takeIf { it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c in "_-+." } } ?: ""
        val type = keys["p"] ?: "title"
        val done = keys["d"] != "0"

        if (type == "?") return KittyNotificationOutcome.Reply("\u001b]99;i=$id:p=?;p=title,body,?:o=always:u=0,1,2\u001b\\")

        if (type == "title" || type == "body") {
            val bytes = if (keys["e"] == "1") {
                try {
                    Base64.getMimeDecoder().decode(text)
                } catch (e: IllegalArgumentException) {
                    pending.remove(id)
                    return KittyNotificationOutcome.None
                }
            } else {
                text
            }
            val entry = pending.getOrPut(id) {
                if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
                Pending()
            }
            if (entry.size + bytes.size > MAX_NOTIFICATION_BYTES) {
                pending.remove(id)
                return KittyNotificationOutcome.None
            }
            (if (type == "title") entry.title else entry.body).write(bytes)
        }

        if (!done) return KittyNotificationOutcome.None
        val entry = pending.remove(id) ?: return KittyNotificationOutcome.None
        val title = clean(entry.title.toString(Charsets.UTF_8), keepNewlines = false)
        val body = clean(entry.body.toString(Charsets.UTF_8), keepNewlines = true)
        if (title.isEmpty() && body.isEmpty()) return KittyNotificationOutcome.None
        return KittyNotificationOutcome.Notify(title, body)
    }

    // What the program says is shown as text on a line (or lines): the controls are not.
    private fun clean(text: String, keepNewlines: Boolean): String =
        text.filter { !it.isISOControl() || (keepNewlines && it == '\n') }.trim()
}
