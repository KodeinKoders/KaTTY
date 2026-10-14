package app.katty.terminal.compose

import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * What the line editing keys of macOS send: Option and Left or Right move by a word, and Command and Left, Right or
 * Backspace go to the start of the line, to its end, and delete what is before the cursor. Terminals do not send them as keys:
 * they send what the line editors (readline, zsh) already bind, which is what Ghostty and iTerm2 do. Encoded, they are
 * `CSI 1 ; 3 D` and the like, which a shell does not know, and prints.
 *
 * Only on macOS, where there is a Command key, and with no other modifier: Shift and Control keep the keys the program's,
 * encoded. The release is mapped as well, for it to be consumed: the program is told nothing of it. `null` for any other key.
 */
internal fun lineEditingText(key: KeyInfo, mac: Boolean = isMac): String? {
    if (!mac || key.kind == KeyInfo.Kind.TYPED || key.shift || key.ctrl) return null
    return when {
        key.alt && !key.meta -> when (key.keyCode) {
            AwtKeyEvent.VK_LEFT -> "\u001bb"
            AwtKeyEvent.VK_RIGHT -> "\u001bf"
            else -> null
        }
        key.meta && !key.alt -> when (key.keyCode) {
            AwtKeyEvent.VK_LEFT -> "\u0001"
            AwtKeyEvent.VK_RIGHT -> "\u0005"
            AwtKeyEvent.VK_BACK_SPACE -> "\u0015"
            else -> null
        }
        else -> null
    }
}
