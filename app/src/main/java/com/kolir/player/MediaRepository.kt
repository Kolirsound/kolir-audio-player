package com.kolir.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "kolir_playback_channel"
        const val NOTIFICATION_ID = 7001

        const val ACTION_PLAY_PAUSE = "com.kolir.player.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.kolir.player.ACTION_NEXT"
        const val ACTION_PREV = "com.kolir.player.ACTION_PREV"
        const val ACTION_STOP = "com.kolir.player.ACTION_STOP"
    }

    interface Listener {
        fun onPlaybackStateChanged()
        fun onPlaybackError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var dspEngine: AudioDspEngine
        private set

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private var audioFocusRequest: AudioFocusRequest? = null

    private var mediaPlayer: MediaPlayer? = null
    private var queue: List<MediaItem> = emptyList()
    private var currentIndex: Int = -1

    var currentItem: MediaItem? = null
        private set
    var isPreparing: Boolean = false
        private set

    private var resumeOnFocusGain: Boolean = false
    private var isNoisyReceiverRegistered: Boolean = false
    private var listener: Listener? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    resumeOnFocusGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mediaPlayer?.setVolume(0.25f, 0.25f)
                } catch (_: Throwable) {
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                } catch (_: Throwable) {
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dspEngine = AudioDspEngine(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        registerNoisyReceiver()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> stopPlaybackAndForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNoisyReceiver()
        releasePlayer()
        abandonAudioFocus()
        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (_: Throwable) {
        }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Throwable) {
            false
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    fun getDuration(): Int {
        val item = currentItem
        if (item?.isStream == true) return 0
        return try {
            val dur = mediaPlayer?.duration ?: 0
            if (dur > 0) dur else (item?.durationMs ?: 0)
        } catch (_: Throwable) {
            item?.durationMs ?: 0
        }
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int) {
        if (items.isEmpty()) return
        queue = items
        currentIndex = startIndex.coerceIn(0, items.lastIndex)
        startItem(queue[currentIndex])
    }

    fun playSingle(item: MediaItem) {
        queue = listOf(item)
        currentIndex = 0
        startItem(item)
    }

    fun togglePlayPause() {
        if (isPreparing) return
        val mp = mediaPlayer
        if (mp == null) {
            val item = currentItem ?: queue.firstOrNull() ?: return
            startItem(item)
            return
        }
        if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Throwable) {
        }
        updateMediaSessionState()
        updateNotification(promoteForeground = false)
        notifyListener()
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        if (!requestAudioFocus()) return
        try {
            mp.start()
            mediaSession.isActive = true
        } catch (_: Throwable) {
        }
        updateMediaSessionState()
        updateNotification(promoteForeground = true)
        notifyListener()
    }

    fun seekTo(positionMs: Int) {
        val item = currentItem ?: return
        if (item.isStream) return
        try {
            mediaPlayer?.seekTo(positionMs.coerceAtLeast(0))
            updateMediaSessionState()
        } catch (_: Throwable) {
        }
    }

    fun playNext() {
        if (queue.isEmpty()) return
        currentIndex = if (currentIndex < 0 || currentIndex + 1 >= queue.size) {
            0
        } else {
            currentIndex + 1
        }
        startItem(queue[currentIndex])
    }

    fun playPrevious() {
        val item = currentItem
        if (item != null && !item.isStream && getCurrentPosition() > 3500) {
            seekTo(0)
            return
        }
        if (queue.isEmpty()) return
        currentIndex = if (currentIndex <= 0) {
            queue.lastIndex
        } else {
            currentIndex - 1
        }
        startItem(queue[currentIndex])
    }

    fun stopPlaybackAndForeground() {
        resumeOnFocusGain = false
        releasePlayer()
        abandonAudioFocus()
        isPreparing = false
        try {
            mediaSession.isActive = false
        } catch (_: Throwable) {
        }
        updateMediaSessionState()
        stopForegroundCompat(removeNotification = true)
        notifyListener()
        stopSelf()
    }

    // ==================== ВНУТРЕННИЙ ДВИЖОК ====================

    private fun startItem(item: MediaItem) {
        ensureServiceStarted()
        releasePlayer()

        currentItem = item
        isPreparing = true
        updateMediaSessionMetadata()
        updateMediaSessionState()
        updateNotification(promoteForeground = true)
        notifyListener()

        if (!requestAudioFocus()) {
            isPreparing = false
            notifyError("Нет аудиофокуса")
            notifyListener()
            return
        }

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                try {
                    setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                } catch (_: Throwable) {
                }

                if (item.isStream) {
                    setDataSource(item.uri.toString())
                } else {
                    setDataSource(this@PlaybackService, item.uri)
                }

                setOnPreparedListener { player ->
                    isPreparing = false
                    dspEngine.attachToSession(player.audioSessionId)
                    player.start()
                    mediaSession.isActive = true
                    updateMediaSessionMetadata()
                    updateMediaSessionState()
                    updateNotification(promoteForeground = true)
                    notifyListener()
                }

                setOnCompletionListener {
                    updateMediaSessionState()
                    notifyListener()
                    if (!item.isStream && queue.size > 1) {
                        playNext()
                    } else {
                        updateNotification(promoteForeground = false)
                    }
                }

                setOnErrorListener { _, what, extra ->
                    isPreparing = false
                    updateMediaSessionState()
                    updateNotification(promoteForeground = false)
                    notifyError("Ошибка воспроизведения ($what/$extra)")
                    notifyListener()
                    true
                }
            }

            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Throwable) {
            isPreparing = false
            notifyError("Не удалось открыть источник: ${e.message}")
            notifyListener()
        }
    }

    private fun ensureServiceStarted() {
        try {
            val intent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releasePlayer() {
        dspEngine.releaseEffects()
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaPlayer?.reset()
        } catch (_: Throwable) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
    }

    // ==================== MEDIA SESSION И ШТОРКА ====================

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "KolirPlayerSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }

                override fun onStop() {
                    stopPlaybackAndForeground()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }
            })
        }
    }

    private fun updateMediaSessionMetadata() {
        val item = currentItem ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration().toLong())
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updateMediaSessionState() {
        val stateCode = when {
            isPreparing -> PlaybackState.STATE_BUFFERING
            isPlaying() -> PlaybackState.STATE_PLAYING
            currentItem != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO

        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                stateCode,
                getCurrentPosition().toLong(),
                if (isPlaying()) 1.0f else 0.0f
            )
            .build()

        mediaSession.setPlaybackState(state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KOLIR Player Воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление музыкой KOLIR Player в фоне"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val item = currentItem
        val title = item?.title ?: "KOLIR Player"
        val subtitle = when {
            isPreparing -> "Загрузка..."
            item != null -> item.artist
            else -> "KOLIR SOUND Engine"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags()
        )

        val prevIntent = serviceActionIntent(ACTION_PREV, 1)
        val playPauseIntent = serviceActionIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = serviceActionIntent(ACTION_NEXT, 3)

        val playPauseIcon = if (isPlaying()) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (isPlaying()) "Пауза" else "Играть"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying() || isPreparing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        addNotificationAction(builder, android.R.drawable.ic_media_previous, "Пред.", prevIntent)
        addNotificationAction(builder, playPauseIcon, playPauseLabel, playPauseIntent)
        addNotificationAction(builder, android.R.drawable.ic_media_next, "След.", nextIntent)

        return builder.build()
    }

    private fun addNotificationAction(
        builder: Notification.Builder,
        iconRes: Int,
        title: String,
        intent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(Notification.Action.Builder(iconRes, title, intent).build())
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(iconRes, title, intent)
        }
    }

    private fun updateNotification(promoteForeground: Boolean) {
        val notification = buildNotification()
        if (promoteForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Throwable) {
                notifyManager(notification)
            }
        } else {
            stopForegroundCompat(removeNotification = false)
            notifyManager(notification)
        }
    }

    private fun notifyManager(notification: Notification) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(
                    if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
                )
            } else {
                @Suppress("DEPRECATION")
                stopForeground(removeNotification)
            }
        } catch (_: Throwable) {
        }
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    // ==================== АУДИОФОКУС И НАУШНИКИ ====================

    private fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Throwable) {
        }
    }

    private fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            isNoisyReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Throwable) {
        }
        isNoisyReceiverRegistered = false
    }

    private fun notifyListener() {
        mainHandler.post { listener?.onPlaybackStateChanged() }
    }

    private fun notifyError(message: String) {
        mainHandler.post { listener?.onPlaybackError(message) }
    }
}
