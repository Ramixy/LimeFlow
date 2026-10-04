package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.DEEPLINK_SCHEME
import io.github.dovecoteescapee.byedpi.databinding.ActivityUrlSchemesBinding
import io.github.dovecoteescapee.byedpi.databinding.ItemUrlSchemeBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences

/**
 * Lists the limeflow:// commands that automation apps (Tasker, MacroDroid,
 * HTTP shortcuts) can fire without any permission, next to the protected
 * ControlReceiver. Tapping "copy" puts the full URL on the clipboard.
 */
class UrlSchemesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUrlSchemesBinding

    private data class Scheme(val command: String, val description: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityUrlSchemesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.schemesBack.setOnClickListener { finish() }
        addSchemes()
    }

    private fun addSchemes() {
        val schemes = listOf(
            Scheme("connect", R.string.url_scheme_connect),
            Scheme("disconnect", R.string.url_scheme_disconnect),
            Scheme("toggle", R.string.url_scheme_toggle),
            Scheme("status", R.string.url_scheme_status),
        )
        val inflater = LayoutInflater.from(this)
        schemes.forEach { scheme ->
            val row = ItemUrlSchemeBinding.inflate(inflater, binding.schemesContainer, true)
            val url = "$DEEPLINK_SCHEME://${scheme.command}"
            row.schemeName.text = url
            row.schemeDescription.setText(scheme.description)
            row.schemeCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(url, url))
                Toast.makeText(this, R.string.dev_logs_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
