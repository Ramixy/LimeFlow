package com.amurcanov.tgwsproxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Step-by-step reachability check of the user's own server from the phone, so
 * a bare "timeout" in the proxy log can be pinned to a stage: IP blocked
 * (TCP), TLS handshake blocked, or the nginx -> Telegram leg. The "split"
 * variant sends the first bytes in tiny TCP segments, like the engine does.
 */
object ServerProbe {

    private const val TIMEOUT_MS = 6_000
    private val SPLIT_PLAN = intArrayOf(1, 5, 16, 24, 32)
    private const val SPLIT_CHUNK = 48
    private const val SPLIT_LIMIT = 2048

    suspend fun run(domain: String, coverSni: String, onLine: (String) -> Unit) = withContext(Dispatchers.IO) {
        val base = domain.trim().lowercase()
        val ip = Regex("""(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\.(sslip|nip)\.io$""")
            .find(base)?.groupValues?.let { "${it[1]}.${it[2]}.${it[3]}.${it[4]}" }
        val target = ip ?: runCatching { java.net.InetAddress.getByName("kws2.$base").hostAddress }.getOrNull()
        if (target == null) {
            onLine("✗ DNS: не удалось узнать IP для kws2.$base")
            return@withContext
        }
        onLine("Сервер: $target (домен $base)")

        step(onLine, "1. TCP $target:22 (SSH)") { Socket().use { it.connect(InetSocketAddress(target, 22), TIMEOUT_MS) } }
        val tcp443 = step(onLine, "2. TCP $target:443") {
            Socket().use { it.connect(InetSocketAddress(target, 443), TIMEOUT_MS) }
        }
        if (!tcp443) {
            onLine("Итог: до порта 443 не доходят пакеты — режут IP/порт в этой сети.")
            return@withContext
        }
        val sni = coverSni.trim().ifEmpty { "kws2.$base" }
        step(onLine, "2b. Без TLS: WebSocket на порт 80 (ждём 101)") {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = TIMEOUT_MS
                s.connect(InetSocketAddress(target, 80), TIMEOUT_MS)
                val req = "GET /tg/2/apiws HTTP/1.1\r\nHost: $sni\r\nConnection: Upgrade\r\n" +
                    "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray()); flush() }
                val status = s.getInputStream().bufferedReader().readLine().orEmpty()
                check(status.contains(" 101 ")) {
                    "ответ: ${status.ifBlank { "пусто" }}" +
                        if (status.contains(" 404 ")) " (обновите сервер: запустите установку ещё раз)" else ""
                }
            }
        }
        val plain = step(onLine, "3. TLS, SNI $sni") { connect(target, sni, split = false).close() }
        val split = step(onLine, "4. TLS с разбивкой (как в прокси), SNI $sni") {
            connect(target, sni, split = true).close()
        }
        if (!plain && !split) {
            onLine("Итог: TCP проходит, а TLS режется даже с разбивкой.")
            return@withContext
        }
        step(onLine, "5. WebSocket к Telegram (ждём 101)") {
            connect(target, sni, split = !plain).use { c ->
                c.write(
                    ("GET /apiws HTTP/1.1\r\nHost: kws2.$base\r\nConnection: Upgrade\r\n" +
                        "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: binary\r\n\r\n")
                        .toByteArray()
                )
                val status = c.readLine()
                check(status.contains(" 101 ")) { "ответ: ${status.ifBlank { "пусто" }}" }
            }
        }
        onLine("Итог: проверка завершена.")
    }

    /**
     * Picks the connection mode for the own server right after installation:
     * false — TLS (plain or split) gets through, true — only plain HTTP on
     * port 80 does, null — neither (the caller keeps the current setting).
     */
    suspend fun detectPlain(domain: String, coverSni: String): Boolean? = withContext(Dispatchers.IO) {
        val base = domain.trim().lowercase()
        val target = Regex("""(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\.(sslip|nip)\.io$""")
            .find(base)?.groupValues?.let { "${it[1]}.${it[2]}.${it[3]}.${it[4]}" }
            ?: runCatching { java.net.InetAddress.getByName("kws2.$base").hostAddress }.getOrNull()
            ?: return@withContext null
        val sni = coverSni.trim().ifEmpty { "kws2.$base" }
        val tlsOk = runCatching { connect(target, sni, split = true).close() }.isSuccess ||
            runCatching { connect(target, sni, split = false).close() }.isSuccess
        if (tlsOk) return@withContext false
        val plainOk = runCatching {
            Socket().use { s ->
                s.soTimeout = TIMEOUT_MS
                s.connect(InetSocketAddress(target, 80), TIMEOUT_MS)
                s.getOutputStream().write(
                    ("GET /tg/2/apiws HTTP/1.1\r\nHost: $sni\r\nConnection: Upgrade\r\n" +
                        "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: binary\r\n\r\n")
                        .toByteArray()
                )
                check(s.getInputStream().bufferedReader().readLine().orEmpty().contains(" 101 "))
            }
        }.isSuccess
        if (plainOk) true else null
    }

    private inline fun step(onLine: (String) -> Unit, name: String, block: () -> Unit): Boolean {
        val started = System.currentTimeMillis()
        return try {
            block()
            onLine("✓ $name — ${System.currentTimeMillis() - started} мс")
            true
        } catch (e: Exception) {
            onLine("✗ $name — ${e.javaClass.simpleName}: ${e.message ?: "-"}")
            false
        }
    }

    private fun connect(ip: String, sni: String, split: Boolean): TlsConn {
        val socket = Socket().apply {
            tcpNoDelay = true
            soTimeout = TIMEOUT_MS
            connect(InetSocketAddress(ip, 443), TIMEOUT_MS)
        }
        return try {
            TlsConn(socket, sni, split).also { it.handshake() }
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    /**
     * Minimal TLS client on SSLEngine: Android's SSLSocket writes straight to
     * the file descriptor, so a wrapping stream could not split its packets.
     */
    private class TlsConn(private val socket: Socket, sni: String, private val split: Boolean) : AutoCloseable {
        private val engine: SSLEngine
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private var netIn: ByteBuffer
        private var appIn: ByteBuffer
        private var sent = 0
        private var writes = 0

        init {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            engine = ctx.createSSLEngine(sni, 443).apply {
                useClientMode = true
                // Android 6 has no SNIHostName; there Conscrypt sends the peer
                // host given to createSSLEngine as SNI.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                }
            }
            netIn = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
            appIn = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
        }

        private fun send(bytes: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val n = if (!split || sent >= SPLIT_LIMIT) len - off
                else minOf(len - off, SPLIT_PLAN.getOrElse(writes) { SPLIT_CHUNK })
                output.write(bytes, off, n)
                output.flush()
                off += n; sent += n; writes++
            }
        }

        private fun wrapAndSend(src: ByteBuffer) {
            val net = ByteBuffer.allocate(engine.session.packetBufferSize)
            do {
                net.clear()
                val r = engine.wrap(src, net)
                check(r.status == SSLEngineResult.Status.OK || r.status == SSLEngineResult.Status.CLOSED) { "wrap ${r.status}" }
                net.flip()
                if (net.hasRemaining()) send(net.array(), net.limit())
            } while (src.hasRemaining())
            runTasks()
        }

        private fun readMore() {
            netIn.compact()
            if (!netIn.hasRemaining()) {
                netIn = ByteBuffer.allocate(netIn.capacity() * 2).put(netIn.flip() as ByteBuffer)
            }
            val n = input.read(netIn.array(), netIn.position(), netIn.remaining())
            check(n >= 0) { "сервер закрыл соединение" }
            netIn.position(netIn.position() + n)
            netIn.flip()
        }

        private fun unwrap(): SSLEngineResult {
            appIn.compact()
            val r = engine.unwrap(netIn, appIn)
            appIn.flip()
            runTasks()
            return r
        }

        private fun runTasks() {
            while (true) engine.delegatedTask?.run() ?: break
        }

        fun handshake() {
            engine.beginHandshake()
            val empty = ByteBuffer.allocate(0)
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapAndSend(empty)
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        val r = unwrap()
                        if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) readMore()
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                    // NEED_UNWRAP_AGAIN on newer runtimes: data already buffered.
                    else -> unwrap()
                }
            }
        }

        fun write(data: ByteArray) = wrapAndSend(ByteBuffer.wrap(data))

        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                while (appIn.hasRemaining()) {
                    val c = appIn.get().toInt().toChar()
                    if (c == '\n') return line.toString().trimEnd('\r')
                    line.append(c)
                }
                val r = unwrap()
                if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) readMore()
                if (r.status == SSLEngineResult.Status.CLOSED) return line.toString()
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
