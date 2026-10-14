package app.katty.terminal

import app.katty.ghostty.GhosttyAutoscroll
import app.katty.ghostty.GhosttyCellStyle
import app.katty.ghostty.GhosttyCellWidth
import app.katty.ghostty.GhosttyClipboardLocation
import app.katty.ghostty.GhosttyCursorStyle
import app.katty.ghostty.GhosttyKey
import app.katty.ghostty.GhosttyKeyAction
import app.katty.ghostty.GhosttyKeyEvent
import app.katty.ghostty.GhosttyMods
import app.katty.ghostty.GhosttyMouseAction
import app.katty.ghostty.GhosttyMouseButton
import app.katty.ghostty.GhosttyMouseEvent
import app.katty.ghostty.GhosttyMouseShape
import app.katty.ghostty.GhosttyRenderCell
import app.katty.ghostty.GhosttyRenderCursor
import app.katty.ghostty.GhosttyRgb
import app.katty.ghostty.GhosttyTerminal
import app.katty.ghostty.GhosttyUnderline

// What the terminal module's API hides of ghostty-vt: this file is where the two are converted.

internal fun GhosttyRgb.toTerminal() = TerminalColor(rgb)

private fun TerminalColor.toGhostty() = GhosttyRgb(rgb)

/**
 * Sets the colors of [theme] as the terminal's defaults, or libghostty's own colors for `null`.
 */
internal fun GhosttyTerminal.applyTheme(theme: TerminalTheme?) {
    if (theme == null) {
        // White on black is what libghostty starts with, but clearing the foreground and the background
        // does not go back to it once they were set, so they are set.
        setDefaultColors(
            foreground = GhosttyRgb(0xFFFFFF),
            background = GhosttyRgb(0x000000),
            cursor = null,
            palette = null,
        )
    } else {
        setDefaultColors(
            foreground = theme.foreground.toGhostty(),
            background = theme.background.toGhostty(),
            cursor = theme.cursor?.toGhostty(),
            palette = generatePalette(theme).map { it.toGhostty() },
        )
    }
}

private fun GhosttyCellStyle.toTerminal(): TerminalStyle =
    if (this == GhosttyCellStyle.NONE) {
        TerminalStyle.NONE
    } else {
        TerminalStyle(
            bold = bold,
            italic = italic,
            faint = faint,
            blink = blink,
            inverse = inverse,
            invisible = invisible,
            strikethrough = strikethrough,
            overline = overline,
            underline = when (underline) {
                GhosttyUnderline.NONE -> TerminalUnderline.NONE
                GhosttyUnderline.SINGLE -> TerminalUnderline.SINGLE
                GhosttyUnderline.DOUBLE -> TerminalUnderline.DOUBLE
                GhosttyUnderline.CURLY -> TerminalUnderline.CURLY
                GhosttyUnderline.DOTTED -> TerminalUnderline.DOTTED
                GhosttyUnderline.DASHED -> TerminalUnderline.DASHED
            },
        )
    }

internal fun GhosttyRenderCell.toTerminal() = TerminalCell(
    // A placeholder is not text to anyone: the image that it says is drawn under it.
    text = if (placeholder != null) "" else text,
    foreground = foreground?.toTerminal(),
    background = background?.toTerminal(),
    style = style.toTerminal(),
    width = when (width) {
        GhosttyCellWidth.NARROW -> TerminalCellWidth.NARROW
        GhosttyCellWidth.WIDE -> TerminalCellWidth.WIDE
        GhosttyCellWidth.SPACER_TAIL -> TerminalCellWidth.SPACER_TAIL
        GhosttyCellWidth.SPACER_HEAD -> TerminalCellWidth.SPACER_HEAD
    },
    hyperlink = hyperlink,
    placeholder = placeholder?.let { TerminalPlaceholder(it.imageIdLow, it.placementId, it.row, it.col, it.high) },
)

internal fun GhosttyRenderCursor.toTerminal() = TerminalCursor(
    x = x,
    y = y,
    visible = visible,
    blinking = blinking,
    shape = when (style) {
        GhosttyCursorStyle.BAR -> TerminalCursorShape.BAR
        GhosttyCursorStyle.BLOCK -> TerminalCursorShape.BLOCK
        GhosttyCursorStyle.UNDERLINE -> TerminalCursorShape.UNDERLINE
        GhosttyCursorStyle.BLOCK_HOLLOW -> TerminalCursorShape.BLOCK_HOLLOW
    },
)

internal fun TerminalCursorShape.toGhostty(): GhosttyCursorStyle = when (this) {
    TerminalCursorShape.BAR -> GhosttyCursorStyle.BAR
    TerminalCursorShape.BLOCK -> GhosttyCursorStyle.BLOCK
    TerminalCursorShape.UNDERLINE -> GhosttyCursorStyle.UNDERLINE
    TerminalCursorShape.BLOCK_HOLLOW -> GhosttyCursorStyle.BLOCK_HOLLOW
}

// Both enums have the same entries, in the same order, which a test checks: indexing is enough.
private val ghosttyKeys: List<GhosttyKey> = TerminalKey.entries.map { GhosttyKey.valueOf(it.name) }

private fun Set<TerminalModifier>.toGhostty(): GhosttyMods =
    fold(GhosttyMods.NONE) { mods, modifier ->
        mods or when (modifier) {
            TerminalModifier.SHIFT -> GhosttyMods.SHIFT
            TerminalModifier.CTRL -> GhosttyMods.CTRL
            TerminalModifier.ALT -> GhosttyMods.ALT
            TerminalModifier.SUPER -> GhosttyMods.SUPER
            TerminalModifier.CAPS_LOCK -> GhosttyMods.CAPS_LOCK
            TerminalModifier.NUM_LOCK -> GhosttyMods.NUM_LOCK
        }
    }

internal fun KeyInput.toGhostty() = GhosttyKeyEvent(
    key = ghosttyKeys[key.ordinal],
    action = when (action) {
        TerminalKeyAction.RELEASE -> GhosttyKeyAction.RELEASE
        TerminalKeyAction.PRESS -> GhosttyKeyAction.PRESS
        TerminalKeyAction.REPEAT -> GhosttyKeyAction.REPEAT
    },
    mods = mods.toGhostty(),
    consumedMods = consumedMods.toGhostty(),
    composing = composing,
    text = text,
    unshiftedCodepoint = unshiftedCodepoint,
)

internal fun MouseInput.toGhostty(xPx: Float, yPx: Float) = GhosttyMouseEvent(
    action = when (action) {
        TerminalMouseAction.PRESS -> GhosttyMouseAction.PRESS
        TerminalMouseAction.RELEASE -> GhosttyMouseAction.RELEASE
        TerminalMouseAction.MOTION -> GhosttyMouseAction.MOTION
    },
    button = button?.let {
        when (it) {
            TerminalMouseButton.LEFT -> GhosttyMouseButton.LEFT
            TerminalMouseButton.RIGHT -> GhosttyMouseButton.RIGHT
            TerminalMouseButton.MIDDLE -> GhosttyMouseButton.MIDDLE
            TerminalMouseButton.WHEEL_UP -> GhosttyMouseButton.WHEEL_UP
            TerminalMouseButton.WHEEL_DOWN -> GhosttyMouseButton.WHEEL_DOWN
            TerminalMouseButton.WHEEL_LEFT -> GhosttyMouseButton.WHEEL_LEFT
            TerminalMouseButton.WHEEL_RIGHT -> GhosttyMouseButton.WHEEL_RIGHT
            TerminalMouseButton.BACK -> GhosttyMouseButton.EIGHT
            TerminalMouseButton.FORWARD -> GhosttyMouseButton.NINE
        }
    },
    mods = mods.toGhostty(),
    x = xPx,
    y = yPx,
)

// Both enums have the same entries, in the same order, which a test checks: the names are enough.
internal fun GhosttyMouseShape.toTerminal(): TerminalPointerShape = TerminalPointerShape.valueOf(name)

internal fun GhosttyAutoscroll.toTerminal(): TerminalAutoscroll = TerminalAutoscroll.valueOf(name)

internal fun GhosttyClipboardLocation.toTerminal(): TerminalClipboardLocation = TerminalClipboardLocation.valueOf(name)

internal fun TerminalClipboardLocation.toGhostty(): GhosttyClipboardLocation = GhosttyClipboardLocation.valueOf(name)
