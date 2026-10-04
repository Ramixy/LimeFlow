package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.data.StrategyShare
import io.github.dovecoteescapee.byedpi.databinding.ActivityStrategyShareBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the current strategy as a limeflow://strategy link: QR code, copy to
 * clipboard or a plain share sheet. Generation only — no camera permission.
 *
 * The QR is rendered off the main thread; a payload that still exceeds the QR
 * capacity (see StrategyShare compression notes) hides the code and falls
 * back to copy/share instead of crashing.
 */
class StrategyShareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStrategyShareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityStrategyShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profile = FlowsealProfiles.selected(getPreferences())
        val link = StrategyShare.buildLink(profile)

        binding.shareTitle.text = profile.name
        binding.shareLink.text = link

        binding.shareBack.setOnClickListener { finish() }
        binding.shareCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LimeFlow strategy", link))
            Toast.makeText(this, R.string.dev_logs_copied, Toast.LENGTH_SHORT).show()
        }
        binding.shareSend.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, link)
            startActivity(Intent.createChooser(intent, getString(R.string.strategy_qr_share)))
        }

        renderQr(link)
    }

    private fun renderQr(link: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { runCatching { buildQr(link) }.getOrNull() }
            if (bitmap == null) {
                // Payload beyond QR capacity: keep copy/share working.
                binding.shareQr.visibility = View.GONE
                binding.shareQrFallback.visibility = View.VISIBLE
            } else {
                binding.shareQr.visibility = View.VISIBLE
                binding.shareQrFallback.visibility = View.GONE
                binding.shareQr.setImageBitmap(bitmap)
            }
        }
    }

    private fun buildQr(content: String, size: Int = 900): Bitmap {
        // WriterException (payload beyond QR capacity) propagates; renderQr
        // falls back to copy/share for it.
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                // Lowest error correction buys the most data capacity.
                EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            ),
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
    }
}
