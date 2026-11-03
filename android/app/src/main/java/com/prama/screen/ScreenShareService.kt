package com.prama.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Foreground service that captures the screen and streams it (video only).
 * Android always shows a notification while this runs — sharing can never be hidden.
 */
class ScreenShareService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "share"
        private const val NOTIF_ID = 7

        @Volatile var running = false
        @Volatile var status = "NOT SHARING"
        /** MainActivity listens to refresh its UI. Called on main thread. */
        var onChange: (() -> Unit)? = null

        fun start(ctx: Context, resultCode: Int, data: Intent, server: String, room: String, name: String) {
            val i = Intent(ctx, ScreenShareService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SERVER, server).putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_NAME, name)
            ctx.startForegroundService(i)
        }

        fun command(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var client: RtcClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> if (!running) begin(intent) else return START_NOT_STICKY
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        createChannel()
        // Must be in foreground BEFORE touching MediaProjection (Android 10+ / 14 rule)
        val n = buildNotification("STARTING…")
        when {
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIF_ID, n)
        }
        running = true

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return }

        try {
            Rtc.init(this)
            val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } } // user stopped from system UI
            })
            val h = SurfaceTextureHelper.create("PramaScreen", Rtc.egl.eglBaseContext)
            val src = Rtc.factory.createVideoSource(true)
            cap.initialize(h, this, src.capturerObserver)
            val (w, ht) = captureSize()
            cap.startCapture(w, ht, 15)
            val t = Rtc.factory.createVideoTrack("screen", src)
            capturer = cap; helper = h; source = src; track = t

            client = RtcClient(
                this,
                serverUrl = intent.getStringExtra(EXTRA_SERVER) ?: "",
                room = intent.getStringExtra(EXTRA_ROOM) ?: "000000",
                role = "sharer",
                name = intent.getStringExtra(EXTRA_NAME) ?: "STAFF",
                pin = "",
                localVideo = t,
                remoteSink = null,
                listener = object : RtcClient.Listener {
                    override fun onStatus(text: String) { main.post { publish(text) } }
                    override fun onError(message: String) { main.post { publish(message); stopSelf() } }
                },
            ).also { it.start() }
        } catch (e: Exception) {
            publish("ERROR: ${e.message ?: "SCREEN CAPTURE FAILED"}".uppercase())
            stopSelf()
        }
    }

    /** Long side max 1280px — sharp enough to read, light on mobile data. */
    private fun captureSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        var w = m.widthPixels
        var h = m.heightPixels
        val scale = 1280f / maxOf(w, h)
        if (scale < 1f) { w = (w * scale).toInt(); h = (h * scale).toInt() }
        return Pair(w / 2 * 2, h / 2 * 2)
    }

    private fun publish(text: String) {
        status = text
        if (running) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
        onChange?.invoke()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "SCREEN SHARING", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("YOUR SCREEN IS BEING SHARED")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "STOP SHARING", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        client?.stop(); client = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        track?.dispose(); track = null
        source?.dispose(); source = null
        helper?.dispose(); helper = null
        status = "NOT SHARING"
        onChange?.invoke()
        super.onDestroy()
    }
}
