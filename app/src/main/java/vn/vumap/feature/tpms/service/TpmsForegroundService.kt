package vn.vumap.feature.tpms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Driving-session foreground service for TPMS BLE.
 *
 * - Started/stopped EXPLICITLY with the user's driving session; no background
 *   BLE scan while disabled.
 * - Android 14+ foreground type `connectedDevice` (+ location when the same
 *   session also tracks GPS; navigation GPS owns its own `location` FGS).
 * - Visible notification + finite reconnect/backoff owned by the transports;
 *   respects Doze / OEM battery management by stopping with the session.
 *
 * Manifest must declare:
 * ```
 * FOREGROUND_SERVICE + FOREGROUND_SERVICE_CONNECTED_DEVICE (+ LOCATION when
 * combined), BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+), and for <=30 the
 * location permission path documented in the TPMS plan.
 * ```
 */
class TpmsForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(
            intent?.getStringExtra(EXTRA_STATUS) ?: "TPMS đang theo dõi",
        )
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val fgsType = if (Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun ensureChannel() {
        // minSdk is 26, so the notification channel API is always available here.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TPMS",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VUMAP TPMS")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "vumap_tpms"
        const val NOTIFICATION_ID = 4201
        const val EXTRA_STATUS = "tpms_status"
    }
}
