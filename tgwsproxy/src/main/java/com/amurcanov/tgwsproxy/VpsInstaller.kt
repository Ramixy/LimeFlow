package com.amurcanov.tgwsproxy

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Sets up the user's own VPS as a Cloudflare-free "CF proxy" over SSH, the way
 * AmneziaVPN installs its server: upload assets/setup-vps.sh, run it as root
 * and stream its output. The script prints `TGWS_RESULT ok domain=<d>` last.
 *
 * The password is used for this session only and never stored.
 */
object VpsInstaller {

    data class Request(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val domain: String,
        val email: String,
    )

    sealed interface Result {
        data class Ok(val domain: String) : Result
        data class Failed(val message: String) : Result
    }

    private const val REMOTE_SCRIPT = "/tmp/tgws-setup.sh"
    private const val RESULT_FILE = "/etc/tgws/result"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val RUN_TIMEOUT_MS = 15 * 60_000L

    suspend fun install(
        context: Context,
        request: Request,
        onLog: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val script = context.assets.open("setup-vps.sh").use { it.readBytes() }
        var session: Session? = null
        try {
            onLog("Подключение к ${request.user}@${request.host}:${request.port}…")
            val jsch = JSch()
            session = jsch.getSession(request.user, request.host, request.port).apply {
                setPassword(request.password)
                // First-use trust, like `ssh` answering "yes": the fingerprint
                // is shown below so the user can compare it with the hoster's.
                setSocketFactory(SplitSocketFactory())
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                setServerAliveInterval(15_000)
                connect(CONNECT_TIMEOUT_MS)
            }
            onLog("Ключ сервера: ${session.hostKey.getFingerPrint(jsch)}")

            exec(session, "cat > $REMOTE_SCRIPT && chmod 700 $REMOTE_SCRIPT", stdin = script) {}
                .let { if (it != 0) return@withContext Result.Failed("не удалось загрузить скрипт (код $it)") }
            onLog("Скрипт загружен, запускаем установку…")

            // Без своего домена sslip.io строится из IP, который ввёл пользователь:
            // он заведомо доступен с телефона. Скрипт сам взял бы исходящий IP
            // сервера, а на VPS с несколькими адресами тот бывает заблокирован.
            val domain = request.domain.trim().ifEmpty {
                val ip = request.host.trim()
                if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) "${ip.replace('.', '-')}.sslip.io" else ""
            }
            if (domain.isNotEmpty()) onLog("Домен: $domain")
            val args = listOf(domain, request.email.trim())
                .dropLastWhile { it.isEmpty() }
                .joinToString(" ") { shellQuote(it) }
            val isRoot = request.user == "root"
            val command = if (isRoot) {
                "bash $REMOTE_SCRIPT $args"
            } else {
                "sudo -S -p '' bash $REMOTE_SCRIPT $args"
            }
            var result: Result? = null
            val exit = exec(
                session,
                "$command 2>&1; rm -f $REMOTE_SCRIPT",
                stdin = if (isRoot) null else (request.password + "\n").toByteArray(),
            ) { line ->
                when {
                    line.startsWith("TGWS_RESULT ok") ->
                        result = Result.Ok(line.substringAfter("domain=").trim())
                    line.startsWith("TGWS_RESULT fail") ->
                        result = Result.Failed(line.removePrefix("TGWS_RESULT fail").trim())
                    else -> onLog(line)
                }
            }
            // The last lines can get lost when the SSH channel closes abruptly
            // (mobile network, sshd timeout). The script also keeps its result
            // in RESULT_FILE, so read it back, reconnecting if needed.
            result ?: readStoredResult(jsch, session!!, request, onLog)
                ?: Result.Failed("скрипт завершился без результата (код $exit)")
        } catch (e: Exception) {
            Result.Failed(describe(e))
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    private suspend fun exec(
        session: Session,
        command: String,
        stdin: ByteArray?,
        onLine: (String) -> Unit,
    ): Int {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val output: InputStream = channel.inputStream
        val input = channel.outputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            if (stdin != null) input.write(stdin)
            input.close()
            val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
            val reader = output.bufferedReader()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) error("установка заняла больше 15 минут")
                val line = reader.readLine() ?: break
                onLine(line.trimEnd())
            }
            while (!channel.isClosed) Thread.sleep(50)
            return channel.exitStatus
        } finally {
            channel.disconnect()
        }
    }

    private suspend fun readStoredResult(
        jsch: JSch,
        current: Session,
        request: Request,
        onLog: (String) -> Unit,
    ): Result? {
        onLog("Вывод оборвался, читаем итог установки с сервера…")
        var fresh: Session? = null
        return try {
            val session = if (current.isConnected) current else {
                jsch.getSession(request.user, request.host, request.port).apply {
                    setPassword(request.password)
                    setSocketFactory(SplitSocketFactory())
                setConfig("StrictHostKeyChecking", "no")
                    setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    connect(CONNECT_TIMEOUT_MS)
                }.also { fresh = it }
            }
            val sudo = if (request.user == "root") "" else "sudo -S -p '' "
            var line: String? = null
            exec(
                session,
                "${sudo}cat $RESULT_FILE",
                stdin = if (sudo.isEmpty()) null else (request.password + "\n").toByteArray(),
            ) { if (it.startsWith("TGWS_RESULT")) line = it }
            when {
                line == null -> null
                line!!.startsWith("TGWS_RESULT ok") -> Result.Ok(line!!.substringAfter("domain=").trim())
                else -> Result.Failed(line!!.removePrefix("TGWS_RESULT fail").trim())
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { fresh?.disconnect() }
        }
    }

    /**
     * SSH through mobile TSPU: the TCP port opens, but the SSH banner/kex to a
     * foreign VPS gets dropped, like TLS. The first bytes go out in tiny TCP
     * segments (the same trick as the proxy's SplitStream), which the DPI does
     * not reassemble.
     */
    private class SplitSocketFactory : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): java.net.Socket =
            java.net.Socket().apply {
                tcpNoDelay = true
                connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }

        override fun getInputStream(socket: java.net.Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream {
            val out = socket.getOutputStream()
            return object : java.io.OutputStream() {
                private val plan = intArrayOf(1, 5, 16, 24, 32)
                private var sent = 0
                private var writes = 0

                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    var o = off
                    var left = len
                    while (left > 0) {
                        val n = if (sent >= 2048) left else minOf(left, plan.getOrElse(writes) { 48 })
                        out.write(b, o, n)
                        out.flush()
                        o += n; left -= n; sent += n; writes++
                    }
                }

                override fun flush() = out.flush()
                override fun close() = out.close()
            }
        }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun describe(e: Exception): String {
        val msg = e.message.orEmpty()
        val hint = when {
            msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                "неверный логин или пароль"
            msg.contains("timeout", true) -> "сервер не отвечает (проверьте IP и порт SSH)"
            msg.contains("UnknownHost", true) || e is java.net.UnknownHostException ->
                "не удалось найти сервер по адресу"
            msg.contains("Connection refused", true) -> "SSH-порт закрыт"
            else -> null
        }
        // The raw SSH error is kept: "timeout" alone can mean a wrong IP, a
        // fail2ban ban or a dead VPS, and only the original text tells them apart.
        val raw = "${e.javaClass.simpleName}: ${msg.ifBlank { "-" }}"
        return if (hint == null) raw else "$hint\n($raw)"
    }
}
