package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the host application's launcher and asks it to show the embedded
 * proxy screen. This keeps notification and quick-tile navigation valid when
 * TG WS Proxy is packaged as a library.
 */
object ProxyUiNavigation {
    const val EXTRA_OPEN_PROXY_UI =
        "com.amurcanov.tgwsproxy.extra.OPEN_PROXY_UI"

    fun launchIntent(context: Context, flags: Int): Intent {
        val launcher = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        return (launcher ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )).apply {
            putExtra(EXTRA_OPEN_PROXY_UI, true)
            addFlags(flags)
        }
    }
}
