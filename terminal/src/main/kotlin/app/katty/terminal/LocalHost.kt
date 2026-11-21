package app.katty.terminal

import app.katty.terminal.platform.terminalPlatform

// What the shells of this machine put in the address of their directory (`$HOST`, `$HOSTNAME`, `hostname`), as it was last read. It is read again
// when an address does not match it ([isThisMachine]): the name is the network's when the user has not set one, so it changes with the Wi-Fi, and a
// shell started after that has the new one.
@Volatile
private var hostName: String? = terminalPlatform.hostName()

/**
 * The name of this machine, as the system has it (`gethostname` on a Unix system), or `null` when it cannot be known: the last one that was read. Not
 * `InetAddress.getLocalHost()`, which asks the network and can take seconds: it is asked on the thread that reads a program's output.
 */
internal fun localHostName(): String? = hostName

/**
 * Whether [host] is this machine, by the name that was read last, and, when it is not, by the name that the system has now: it changes with the
 * network, and a shell that was started since says the new one. The new name is then the one that is kept. `gethostname` is a quick call of the
 * system, and an address that is not this machine's is the rare case (a shell over `ssh`).
 *
 * @param remembered The name that was read last.
 * @param current Reads the name now.
 * @param remember Keeps the name that was read.
 */
internal fun isThisMachine(
    host: String,
    remembered: () -> String? = ::localHostName,
    current: () -> String? = terminalPlatform::hostName,
    remember: (String) -> Unit = { hostName = it },
): Boolean {
    val before = remembered()
    if (isLocalHost(host, before)) return true
    val now = current()
    if (now == null || now == before) return false
    remember(now)
    return isLocalHost(host, now)
}

/**
 * Whether [host], the host of a `file://` address, is this machine: none, `localhost`, or its name, whatever the case and with or without
 * its domain (`mac` and `mac.local`).
 */
internal fun isLocalHost(host: String, name: String? = localHostName()): Boolean {
    if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) return true
    if (name == null) return false
    return host.equals(name, ignoreCase = true) || host.substringBefore('.').equals(name.substringBefore('.'), ignoreCase = true)
}
