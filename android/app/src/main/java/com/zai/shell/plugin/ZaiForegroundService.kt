package com.zai.shell.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ForegroundService - mantiene la app viva en background
 *
 * Muestra una notificacion persistente (requerido por Android para foreground services).
 * Esto evita que el sistema mate la app cuando el usuario cambia a otra app o la pantalla
 * se apaga. El WebView sigue corriendo y la conexion SSE no se corta.
 */
class ZaiForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "zai_shell_foreground"
        const val CHANNEL_NAME = "Z.ai Shell Active"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ZaiForeground"

        fun start(context: Context) {
            val intent = Intent(context, ZaiForegroundService::class.java)
            intent.action = "START"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Foreground service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // HIGH #14: use stopService instead of startService (blocked on Android 8+ background)
            val intent = Intent(context, ZaiForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        // HIGH #11: Android 14+ requires foregroundServiceType in startForeground call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            try {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground with type failed, falling back: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Z.ai Shell esta activo en segundo plano"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Z.ai Shell")
            .setContentText("Activo - la conversacion no se congelara")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ZaiShell::ForegroundWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
            // CRITICAL #13: acquire without timeout - the service runs forever while app is alive
            // (was 10min, never renewed despite the comment)
            wakeLock?.acquire()
            Log.i(TAG, "Wake lock acquired (no timeout)")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            Log.i(TAG, "Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
