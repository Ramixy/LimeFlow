package com.amurcanov.tgwsproxy

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

object ProxyTileSync {
    fun request(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, ProxyTileService::class.java),
            )
        }
    }
}
