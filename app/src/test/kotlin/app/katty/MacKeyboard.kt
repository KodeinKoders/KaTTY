package app.katty

/**
 * The shortcuts of a Mac, for the tests that press ⌘ and spell the keys as a Mac writes them: they set it in [Shortcuts.map], which the labels and
 * the keys of the windows follow, so that they run the same on a PC (see [PcShortcutsTest] for its own).
 */
internal val MAC_SHORTCUTS: ShortcutMap = ShortcutMap.defaults(KeyboardStyle.MAC)
