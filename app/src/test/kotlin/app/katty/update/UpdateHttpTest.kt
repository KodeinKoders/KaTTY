package app.katty.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The client against a real HTTPS server of the test, with a certificate made for it, which the client is told to trust. */
class UpdateHttpTest {
    private val folder = createTempDirectory("katty-http")
    private val payload = Random(42).nextBytes(300_000)
    private val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))

    private val keyStore: KeyStore = run {
        val file = folder.resolve("server.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", file.toString(), "-storepass", "secret", "-storetype", "PKCS12",
        ).redirectErrorStream(true).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "secret".toCharArray()) } }
    }

    private val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "secret".toCharArray()) }
        httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) })
        createContext("/latest/download/latest.json") { it.redirect("/download/v2/latest.json") }
        createContext("/download/v2/latest.json") { it.answer(200, """{"version":"1.0.2"}""".toByteArray()) }
        createContext("/KaTTY.dmg") { it.answer(200, payload) }
        createContext("/missing") { it.answer(404, ByteArray(0)) }
        start()
    }

    private val base = "https://localhost:${server.address.port}"

    private val trusting: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }.trustManagers, null)
    }

    private fun client(proxy: ProxySelector = HttpClient.Builder.NO_PROXY) = UpdateHttp("1.0.1", proxy) { trusting }

    @AfterTest
    fun stop() {
        server.stop(0)
        deleteRecursively(folder)
    }

    private fun HttpExchange.answer(status: Int, body: ByteArray) {
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
        close()
    }

    private fun HttpExchange.redirect(to: String) {
        responseHeaders.add("Location", to)
        sendResponseHeaders(302, -1)
        close()
    }

    @Test
    fun theManifestIsReadThroughTheRedirectOfTheLatestRelease() {
        assertEquals("""{"version":"1.0.2"}""", client().text(URI("$base/latest/download/latest.json")))
    }

    @Test
    fun anAnswerThatIsNotOkIsAFailure() {
        assertFailsWith<IOException> { client().text(URI("$base/missing")) }
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefused() {
        assertFailsWith<IOException> { client().text(URI("http://localhost:${server.address.port}/latest/download/latest.json")) }
    }

    @Test
    fun aServerThatTheClientDoesNotTrustIsRefused() {
        val untrusting = UpdateHttp("1.0.1", HttpClient.Builder.NO_PROXY)
        assertFailsWith<IOException> { untrusting.text(URI("$base/latest/download/latest.json")) }
    }

    @Test
    fun theDiskImageIsDownloadedWhole() {
        val target = folder.resolve("KaTTY-1.0.2.dmg")
        var last = 0L
        client().download(URI("$base/KaTTY.dmg"), target, payload.size.toLong(), sha) { last = it }
        assertContentEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), last)
        assertFalse(Files.exists(folder.resolve("KaTTY-1.0.2.dmg.part")))
    }

    @Test
    fun aDiskImageThatIsNotTheOneOfTheManifestLeavesNothing() {
        val target = folder.resolve("KaTTY-1.0.2.dmg")
        val wrongSha = "0".repeat(64)
        assertFailsWith<IOException> { client().download(URI("$base/KaTTY.dmg"), target, payload.size.toLong(), wrongSha) {} }
        assertFailsWith<IOException> { client().download(URI("$base/KaTTY.dmg"), target, payload.size.toLong() + 1, sha) {} }
        assertFailsWith<IOException> { client().download(URI("$base/KaTTY.dmg"), target, payload.size.toLong() - 1, sha) {} }
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(folder.resolve("KaTTY-1.0.2.dmg.part")))
    }

    @Test
    fun theRequestsGoThroughTheProxy() {
        ConnectProxy().use { proxy ->
            val text = client(ProxySelector.of(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port))).text(URI("$base/latest/download/latest.json"))
            assertEquals("""{"version":"1.0.2"}""", text)
            assertTrue(proxy.tunnels.get() >= 1, "nothing went through the proxy")
        }
    }

    /** A proxy that only tunnels (`CONNECT`), which is what an HTTPS request asks of one, and counts its tunnels. */
    private class ConnectProxy : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        val tunnels = AtomicInteger()

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread { tunnel(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun tunnel(client: Socket) = client.use {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return
                head.append(byte.toChar())
            }
            val target = head.lineSequence().first().split(' ')[1]
            if (!head.startsWith("CONNECT ")) return
            Socket(target.substringBeforeLast(':'), target.substringAfterLast(':').toInt()).use { upstream ->
                tunnels.incrementAndGet()
                client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                val back = Thread { runCatching { upstream.getInputStream().transferTo(client.getOutputStream()) } }.apply { isDaemon = true; start() }
                runCatching { input.transferTo(upstream.getOutputStream()) }
                back.join(5_000)
            }
        }

        override fun close() = socket.close()
    }
}
