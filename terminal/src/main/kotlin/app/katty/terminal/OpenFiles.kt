package app.katty.terminal

import app.katty.terminal.platform.terminalPlatform

/**
 * The paths of the files that the process [pid] has open (the ones that have a path: not sockets or pipes), or an empty list when it is
 * gone, is not ours to read, or the system does not say. It never throws: it is asked about every agent that runs.
 *
 * It is how a program that says nothing of its own conversation can be told from what it holds: a file it opened for it.
 */
public fun openFilePaths(pid: Int): List<String> = terminalPlatform.processes.openFilePaths(pid)
