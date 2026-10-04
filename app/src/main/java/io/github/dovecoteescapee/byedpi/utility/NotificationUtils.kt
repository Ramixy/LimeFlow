package io.github.dovecoteescapee.byedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.PAUSE_ACTION
import io.github.dovecoteescapee.byedpi.data.RESUME_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    content: CharSequence,
    service: Class<*>,
    paused: Boolean = false,
    bigText: CharSequence? = null,
    vpn: Boolean = false,
    prevStrategy: PendingIntent? = null,
    nextStrategy: PendingIntent? = null,
): Notification {
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
            .setContentTitle(context.getString(title))
            .setContentText(content)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    // Pause is only meaningful for the VPN: the tunnel stays up while the
    // engine is idle. The proxy service keeps its Stop action only.
    if (vpn) {
        if (prevStrategy != null && nextStrategy != null) {
            builder.addAction(0, "◀", prevStrategy)
            builder.addAction(0, "▶", nextStrategy)
        }
        builder.addAction(
            0,
            context.getString(if (paused) R.string.notification_resume else R.string.notification_pause),
            PendingIntent.getService(
                context,
                1,
                Intent(context, service).setAction(if (paused) RESUME_ACTION else PAUSE_ACTION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }

    builder.addAction(
        0,
        context.getString(R.string.notification_stop),
        PendingIntent.getService(
            context,
            0,
            Intent(context, service).setAction(STOP_ACTION),
            PendingIntent.FLAG_IMMUTABLE,
        )
    )

    if (bigText != null) {
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
    }

    return builder.build()
}
