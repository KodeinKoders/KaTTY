package app.katty.core

/** Whether the application runs on Windows: what differs there only by a value (a path, a library's name) is a condition on it. */
public val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Whether the application runs on macOS. */
public val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
