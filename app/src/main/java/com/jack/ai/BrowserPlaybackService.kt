package com.jack.ai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.jack.ai.api.BrowserPages
import com.jack.ai.ui.BrowserActivity
import com.jack.ai.ui.JackBrowser

/** 网页的声音留在原标签页，服务只负责保活和系统播放控制。 */
class BrowserPlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private var idleSince = 0L
    private val tick = object : Runnable {
        override fun run() {
            var t = JackBrowser.tabs.find { it.id == tabId }
            if (t?.audible != true) {
                JackBrowser.tabs.lastOrNull { it.audible && it.mediaFromUser }?.let { t = it; tabId = it.id }
            }
            val playingTab = t ?: run { stopSelf(); return }
            if (playingTab.audible) idleSince = 0 else if (idleSince == 0L) idleSince = System.currentTimeMillis()
            if (idleSince != 0L && System.currentTimeMillis() - idleSince >= 60_000) { stopSelf(); return }
            update(playingTab)
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "网页播放", NotificationManager.IMPORTANCE_LOW))
        session = MediaSessionCompat(this, "网页播放").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = control("play")
                override fun onPause() = control("pause")
                override fun onStop() = control("stop")
            })
            isActive = true
        }
    }
    private fun control(action: String) {
        JackBrowser.mediaControl(tabId, action)
        if (action == "stop") stopSelf()
    }
    private fun update(t: JackBrowser.Tab) {
        val title = if (t.incognito) "隐身标签页" else t.title.ifBlank { BrowserPages.host(t.address) }
        val site = if (t.incognito) "" else BrowserPages.host(t.address)
        session.setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, site).build())
        session.setPlaybackState(PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(if (t.audible) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (t.audible) 1f else 0f).build())
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, BrowserPlaybackService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle(title).setContentText(site).setContentIntent(open).setOngoing(t.audible).setOnlyAlertOnce(true)
            .addAction(if (t.audible) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (t.audible) "暂停" else "播放", action(if (t.audible) "pause" else "play"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action("stop"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
        ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        intent?.action?.takeIf { it in listOf("play", "pause", "stop") }?.let { control(it) }
        handler.removeCallbacks(tick)
        if (intent?.action != "stop") tick.run()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session.release()
        current = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "browser_playback"
        private const val ID = 7310
        private var tabId = -1
        private var current: BrowserPlaybackService? = null
        fun start(ctx: Context, t: JackBrowser.Tab) {
            tabId = t.id
            if (current != null) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, BrowserPlaybackService::class.java)) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BrowserPlaybackService::class.java)) }
    }
}
