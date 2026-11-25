package app.katty.update

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.HexFormat
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

private val logger = LoggerFactory.getLogger("app.katty.update.UpdateHttp")

/**
 * What the updates fetch: the manifest and the disk image, in `https` (a `file` address only for a feed given for a test), through the proxy
 * that macOS is set to (`java.net.useSystemProxies`, which `main` sets before anything goes on the network), and trusting the authorities of the
 * keychain as well as Java's, so that a proxy of a company that inspects TLS with its own authority is trusted as Safari trusts it.
 * Nothing is sent about the user: the `User-Agent` is the application and its version.
 */
internal class UpdateHttp(
    version: String,
    proxy: ProxySelector = ProxySelector.getDefault(),
    sslContext: (() -> SSLContext) = ::keychainTrustingContext,
) {
    private val userAgent = "KaTTY/$version"

    // Made at the first request, off the UI thread: reading the keychain takes a moment.
    private val client by lazy {
        HttpClient.newBuilder()
            // releases/latest/download sends to the file of the latest release, then to GitHub's storage. NORMAL never goes from https to http.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(proxy)
            .sslContext(sslContext())
            .connectTimeout(Duration.ofSeconds(20))
            .build()
    }

    private fun request(uri: URI): HttpRequest {
        if (uri.scheme?.lowercase() != "https") throw IOException("$uri is not an https address")
        return HttpRequest.newBuilder(uri).header("User-Agent", userAgent).timeout(Duration.ofMinutes(2)).GET().build()
    }

    /** The text at [uri]. */
    fun text(uri: URI): String {
        if (uri.scheme == "file") return Files.readString(Path.of(uri))
        val response = client.send(request(uri), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("$uri answered ${response.statusCode()}")
        return response.body()
    }

    /**
     * Writes what is at [uri] to [target], through a file next to it that is moved in place only when it is complete, has [size] bytes and the
     * SHA-256 [sha256]: a download that is cut or altered leaves nothing. [progress] is told the bytes read so far.
     */
    fun download(uri: URI, target: Path, size: Long, sha256: String, progress: (Long) -> Unit) {
        Files.createDirectories(target.parent)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = if (uri.scheme == "file") {
                Files.newInputStream(Path.of(uri))
            } else {
                val response = client.send(request(uri), HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() != 200) {
                    response.body().close()
                    throw IOException("$uri answered ${response.statusCode()}")
                }
                response.body()
            }
            var read = 0L
            input.use { stream ->
                Files.newOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > size) throw IOException("the disk image is bigger than the manifest says")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(read)
                    }
                }
            }
            if (read != size) throw IOException("the disk image has $read bytes, the manifest says $size")
            val actual = HexFormat.of().formatHex(digest.digest())
            if (actual != sha256) throw IOException("the SHA-256 of the disk image is not the one of the manifest")
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(part)
        }
    }
}

/**
 * TLS that trusts Java's authorities and the ones of the macOS keychain: the system's roots and what an administrator trusted
 * (`KeychainStore-ROOT`), and the user's (`KeychainStore`). A store that cannot be read is left out.
 */
internal fun keychainTrustingContext(): SSLContext {
    val managers = buildList {
        add(trustManager(null))
        if (System.getProperty("os.name").startsWith("Mac")) {
            for (type in listOf("KeychainStore-ROOT", "KeychainStore")) {
                try {
                    val store = KeyStore.getInstance(type).apply { load(null, null) }
                    add(trustManager(store))
                } catch (e: Exception) {
                    logger.debug("The keychain store {} is not read", type, e)
                }
            }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(AnyOfTrustManager(managers)), null) }
}

private fun trustManager(store: KeyStore?): X509ExtendedTrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()

/** Trusts a server that one of [managers] trusts: each checks the chain and, given the engine, the name of the host. */
private class AnyOfTrustManager(private val managers: List<X509ExtendedTrustManager>) : X509ExtendedTrustManager() {
    private fun anyOf(check: (X509ExtendedTrustManager) -> Unit) {
        var failure: CertificateException? = null
        for (manager in managers) {
            try {
                check(manager)
                return
            } catch (e: CertificateException) {
                failure = failure ?: e
            }
        }
        throw failure ?: CertificateException("no trust manager")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = anyOf { it.checkServerTrusted(chain, authType) }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = anyOf { it.checkServerTrusted(chain, authType, socket) }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = anyOf { it.checkServerTrusted(chain, authType, engine) }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = anyOf { it.checkClientTrusted(chain, authType) }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = anyOf { it.checkClientTrusted(chain, authType, socket) }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = anyOf { it.checkClientTrusted(chain, authType, engine) }
    override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
}
