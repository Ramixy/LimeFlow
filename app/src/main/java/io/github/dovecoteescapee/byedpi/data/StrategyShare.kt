package io.github.dovecoteescapee.byedpi.data

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Strategy exchange via limeflow://strategy?data=... links (base64url JSON).
 *
 * Strategy arguments embed whole fake-payload hex blobs, so the raw JSON can
 * reach 4-5 KB while a QR code tops out near 2.9 KB — that overflow is a hard
 * zxing WriterException. The payload is therefore gzipped ("2:" prefix);
 * the plain form ("1:") is kept for compatibility with older links.
 */
object StrategyShare {
    const val HOST = "strategy"
    const val DATA_PARAM = "data"

    fun buildLink(profile: FlowsealProfile): String {
        val payload = JSONObject()
            .put("name", profile.name)
            .put("arguments", profile.arguments)
        val data = compress(payload.toString())
        return "$DEEPLINK_SCHEME://$HOST?$DATA_PARAM=$data"
    }

    fun parse(data: String?): FlowsealProfile? = runCatching {
        val decoded = decompress(data?.trim() ?: return null)
        val payload = JSONObject(decoded)
        val name = payload.optString("name").trim()
        val arguments = payload.optString("arguments").trim()
        if (name.isEmpty() || !arguments.startsWith("-")) return null
        FlowsealProfile(
            id = "imported_${System.currentTimeMillis()}",
            name = name,
            method = "импортированная",
            description = "Стратегия, полученная по ссылке LimeFlow",
            arguments = arguments,
            custom = true,
            kind = ProfileKind.CUSTOM,
            badge = "Импорт",
        )
    }.getOrNull()

    private fun compress(json: String): String {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val gzipped = ByteArrayOutputStream(bytes.size).use { sink ->
            GZIPOutputStream(sink).use { it.write(bytes) }
            sink.toByteArray()
        }
        val packed = Base64.encodeToString(
            gzipped,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "2:$packed"
    }

    private fun decompress(data: String): String {
        val (payload, gzipped) = if (data.startsWith("2:")) {
            data.substring(2) to true
        } else if (data.startsWith("1:")) {
            data.substring(2) to false
        } else {
            data to false
        }
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val raw = if (gzipped) {
            ByteArrayInputStream(bytes).use { source ->
                GZIPInputStream(source).use { it.readBytes() }
            }
        } else {
            bytes
        }
        return String(raw, StandardCharsets.UTF_8)
    }
}
