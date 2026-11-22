package app.katty.core

/**
 * The tints that the badges of the environments are drawn in, in the order of the application's palette, which has a light and a dark color for
 * each. An environment that was given none has the one that its name falls on.
 */
public enum class BadgeTint {
    TEAL, VIOLET, ORANGE, PINK, GREEN, BLUE, OLIVE;

    /** Its name as the user reads it: "Orange". */
    public val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** The color that the user gave the badge of an environment, kept in [Settings.environmentColors] as its [encoded] form. */
public sealed interface EnvironmentColor {
    /** How it is kept: the name of a tint in lower case (`orange`), or `#RRGGBB`. */
    public val encoded: String

    /** What it is called to the user: the name of its tint, or its hex. */
    public val label: String

    /** One of the tints of the palette, which follows the theme. */
    public data class Tint(val tint: BadgeTint) : EnvironmentColor {
        override val encoded: String get() = tint.name.lowercase()
        override val label: String get() = tint.label
    }

    /** A color of the user's, [rgb] being `0xRRGGBB`: drawn as it is in both themes. */
    public data class Hex(val rgb: Int) : EnvironmentColor {
        init {
            require(rgb in 0..0xFFFFFF) { "Not an RGB color: $rgb" }
        }

        override val encoded: String get() = "#%06X".format(rgb)
        override val label: String get() = encoded
    }

    public companion object {
        /** The color kept as [text], `null` for one that is neither a tint nor a hex (which the badge then ignores). */
        public fun decode(text: String): EnvironmentColor? =
            BadgeTint.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }?.let(::Tint) ?: parseHex(text)

        /** The hex that the user typed: six hex digits, in any case, with or without a `#` before them; `null` for anything else. */
        public fun parseHex(text: String): Hex? {
            val digits = text.trim().removePrefix("#")
            if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return Hex(digits.toInt(16))
        }
    }
}

/** The color that the user gave the badges of the environment [name], `null` for none (the one its name falls on). */
public fun Settings.environmentColor(name: String): EnvironmentColor? = environmentColors[name]?.let(EnvironmentColor::decode)

/** These settings with the badges of the environment [name] in [color], or in the one its name falls on when it is `null`. */
public fun Settings.withEnvironmentColor(name: String, color: EnvironmentColor?): Settings =
    copy(environmentColors = if (color == null) environmentColors - name else environmentColors + (name to color.encoded))
