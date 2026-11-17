package com.dddylan.slick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Android only allows playback capture from a foreground service that holds the
 * screen-capture consent. This service owns that consent and the capture thread.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var route: CaptureRoute? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio capture", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Slick is listening")
            .setContentText("Visualizing the audio playing on this phone")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data!!)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) AudioEngine.status("cap", "error", "Capture was stopped by Android. Phone audio still works.")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
            route = CaptureRoute(mp).also { it.start() }
            AudioEngine.route = "capture"
            AudioEngine.status("cap", "running", "Capturing phone audio at full quality.")
        } catch (e: Exception) {
            AudioEngine.status("cap", "error", "Capture could not start on this phone (${e.javaClass.simpleName}). Use Phone audio.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        route?.stop()
        route = null
        projection?.stop()
        projection = null
        if (AudioEngine.route == "capture") AudioEngine.route = "off"
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "capture"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
