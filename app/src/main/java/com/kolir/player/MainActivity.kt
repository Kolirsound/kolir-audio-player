package com.kolir.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), PlaybackService.Listener {

    companion object {
        private const val REQ_AUDIO_FILE = 1001
        private const val REQ_PERMISSIONS = 1002
        private const val PERM_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
        private const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }

    private lateinit var mediaRepository: MediaRepository
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false

    // Данные вкладок
    private var allTracks: List<MediaItem> = emptyList()
    private var filteredTracks: List<MediaItem> = emptyList()
    private var streams: List<MediaItem> = emptyList()
    private var searchQuery: String = ""
    private var isSeeking = false
    private var updatingUiProgrammatically = false

    // Вкладки
    private lateinit var tabPlayerBtn: Button
    private lateinit var tabTracksBtn: Button
    private lateinit var tabRadioBtn: Button
    private lateinit var playerTabContainer: ScrollView
    private lateinit var tracksTabContainer: LinearLayout
    private lateinit var radioTabContainer: LinearLayout

    // UI: Плеер и DSP
    private lateinit var spectrumView: NeonSpectrumView
    private lateinit var trackTitleView: TextView
    private lateinit var trackArtistView: TextView
    private lateinit var dspStatusView: TextView
    private lateinit var timeCurrentView: TextView
    private lateinit var timeTotalView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var playPauseBtn: Button
    private lateinit var aiSwitch: Switch
    private lateinit var limiterSwitch: Switch
    private lateinit var presetRow: LinearLayout
    private lateinit var bassValueView: TextView
    private lateinit var bassSeekBar: SeekBar
    private val presetButtons = mutableMapOf<String, Button>()
    private val bandLabelViews = mutableListOf<TextView>()
    private val bandValueViews = mutableListOf<TextView>()
    private val bandSeekBars = mutableListOf<SeekBar>()

    // UI: Библиотека и Радио
    private lateinit var libraryStatusView: TextView
    private lateinit var trackAdapter: MediaListAdapter
    private lateinit var streamAdapter: MediaListAdapter
    private lateinit var radioStatusView: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            val service = playbackService
            if (service != null && !isSeeking) {
                val item = service.currentItem
                if (item?.isStream == true) {
                    progressBar.max = 100
                    progressBar.progress = if (service.isPlaying()) 100 else 0
                    timeCurrentView.text = if (service.isPlaying()) "LIVE" else "ОСТАНОВЛЕНО"
                    timeTotalView.text = "ПОТОК"
                } else if (item != null) {
                    val dur = service.getDuration().coerceAtLeast(1)
                    val pos = service.getCurrentPosition().coerceIn(0, dur)
                    progressBar.max = dur
                    progressBar.progress = pos
                    timeCurrentView.text = formatTime(pos)
                    timeTotalView.text = formatTime(dur)
                }
            }
            uiHandler.postDelayed(this, 300L)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PlaybackService.LocalBinder ?: return
            val service = localBinder.getService()
            playbackService = service
            isServiceBound = true

            service.setListener(this@MainActivity)
            service.dspEngine.onSpectrumUpdated = { bars ->
                spectrumView.updateBars(bars)
            }
            service.dspEngine.onStateChanged = {
                syncDspUiFromEngine()
            }
            service.dspEngine.refreshVisualizerPermission()

            buildPresetButtonsIfNeeded(service.dspEngine)
            syncPlaybackUiFromService()
            syncDspUiFromEngine()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaRepository = MediaRepository(this)
        streams = mediaRepository.loadStreams()

        setContentView(buildUi())
        switchTab(0)
        bindToPlaybackService()

        requestNeededPermissions()
        if (hasStoragePermission()) {
            loadDeviceTracks()
        }
        updateRadioUi()
        uiHandler.post(progressTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(progressTicker)
        playbackService?.let { service ->
            service.setListener(null)
            service.dspEngine.onSpectrumUpdated = null
            service.dspEngine.onStateChanged = null
        }
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Throwable) {
            }
            isServiceBound = false
        }
    }

