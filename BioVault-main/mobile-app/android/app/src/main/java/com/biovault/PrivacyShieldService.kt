package com.biovault

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.biovault.sdk.ConsentBroadcaster

/**
 * Foreground service that continuously broadcasts this device's VitalsID
 * via BLE advertisements so nearby capturers can detect the subject.
 *
 * Also periodically polls Firebase for privacy violation alerts.
 */
class PrivacyShieldService : Service() {

    private var broadcaster: ConsentBroadcaster? = null
    private var vitalsIdHash: String = ""

    companion object {
        const val TAG = "PrivacyShield"
        const val CHANNEL_ID = "vitalsnet_privacy_shield"
        const val NOTIFICATION_ID = 9001
        const val EXTRA_VITALS_ID = "vitals_id_hash"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "PrivacyShieldService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        vitalsIdHash = intent?.getStringExtra(EXTRA_VITALS_ID) ?: ""
        if (vitalsIdHash.isEmpty()) {
            Log.w(TAG, "No VitalsID provided, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        broadcaster = ConsentBroadcaster(applicationContext)
        broadcaster?.startVitalsIdBroadcast(vitalsIdHash)
        Log.i(TAG, "Privacy Shield ACTIVE — broadcasting VitalsID: ${vitalsIdHash.take(8)}...")

        return START_STICKY
    }

    override fun onDestroy() {
        broadcaster?.stopVitalsIdBroadcast()
        broadcaster = null
        Log.i(TAG, "Privacy Shield STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Privacy Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VitalsNet Privacy Shield is protecting your identity"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Privacy Shield Active")
            .setContentText("Your VitalsID is being broadcast via BLE")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
