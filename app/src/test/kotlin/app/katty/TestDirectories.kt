package app.katty

import app.katty.core.isWindows

/**
 * A folder that is there, for the terminals of the tests to start in: `/tmp` on a Unix system, and on Windows the temporary folder, since a
 * console cannot start in a folder that is not there (`/tmp` is `\tmp` of the current drive there).
 */
internal val tmpDirectory: String = if (isWindows) System.getProperty("java.io.tmpdir").trimEnd('\\') else "/tmp"
