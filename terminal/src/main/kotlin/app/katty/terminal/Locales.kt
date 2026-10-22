package app.katty.terminal

import java.io.File
import java.util.Locale

/**
 * The locale that a program is given when the application has none: an application opened from the Finder or the Dock gets no `LANG` from
 * launchd, and in the C locale the line editors of zsh and bash take the bytes of an accented character for Meta keys. It is the user's
 * language and region in UTF-8 when the system has that locale (`fr_FR.UTF-8`), and `en_US.UTF-8` otherwise (there is no `en_FR`).
 *
 * @param installed Whether the system has the locale of that name.
 */
internal fun defaultUtf8Locale(
    locale: Locale = Locale.getDefault(),
    installed: (String) -> Boolean = { File("/usr/share/locale", it).isDirectory },
): String {
    val name = if (locale.language.isNotEmpty() && locale.country.isNotEmpty()) "${locale.language}_${locale.country}.UTF-8" else null
    return name?.takeIf(installed) ?: FALLBACK_LOCALE
}

private const val FALLBACK_LOCALE = "en_US.UTF-8"

/** Gives [environment] a UTF-8 `LANG` when none of the variables that choose the character set has a value. */
internal fun ensureUtf8Locale(environment: MutableMap<String, String>, locale: () -> String = { defaultUtf8Locale() }) {
    if (listOf("LC_ALL", "LC_CTYPE", "LANG").all { environment[it].isNullOrEmpty() }) environment["LANG"] = locale()
}
