package com.jack.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.jack.ai.ui.BrowserActivity
import com.jack.ai.ui.PageReader

/** 「朗读这页」切到后台、锁屏时保活，通知栏和锁屏上能暂停、继续、停（耳机按键也行）。念的事在 PageReader。 */
class ReadAloudService : Service() {
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "朗读网页", NotificationManager.IMPORTANCE_LOW))
        session = MediaSessionCompat(this, "朗读网页").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PageReader.resume()
                override fun onPause() = PageReader.pause()
                override fun onStop() = PageReader.stop()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update()
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            "toggle" -> PageReader.toggle()
            "stop" -> PageReader.stop()
        }
        if (!PageReader.active) stopSelf()
        return START_NOT_STICKY
    }

    private fun update() {
        val title = PageReader.title ?: "朗读网页"
        val playing = PageReader.active && !PageReader.paused
        val progress = if (PageReader.total > 0) "第 ${PageReader.index + 1} / ${PageReader.total} 段" else ""
        session.setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, progress).build())
        session.setPlaybackState(PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, ReadAloudService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle(title).setContentText("朗读中 · $progress").setContentIntent(open).setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续", action("toggle"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action("stop"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
        ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    override fun onDestroy() {
        session.release()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val ID = 7311
        private var current: ReadAloudService? = null

        fun start(ctx: Context) {
            current?.let { it.update(); return }
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ReadAloudService::class.java)) }
        }

        /** 念到下一段、暂停、继续时更新通知。 */
        fun refresh() { runCatching { current?.update() } }

        fun stop() {
            current?.let {
                ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                it.stopSelf()
            }
        }
    }
}
