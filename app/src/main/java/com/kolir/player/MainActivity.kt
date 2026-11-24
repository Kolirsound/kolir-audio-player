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

    private fun bindToPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ==================== CALLBACKS ОТ PLAYBACK SERVICE ====================

    override fun onPlaybackStateChanged() {
        syncPlaybackUiFromService()
    }

    override fun onPlaybackError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        syncPlaybackUiFromService()
    }

    private fun syncPlaybackUiFromService() {
        val service = playbackService
        val item = service?.currentItem

        if (item == null) {
            trackTitleView.text = "Выберите трек или радиопоток"
            trackArtistView.text = "KOLIR Core v1.0 • Фон и шторка активны"
            playPauseBtn.text = "PLAY"
            timeCurrentView.text = "0:00"
            timeTotalView.text = "0:00"
            progressBar.progress = 0
            spectrumView.decayToZero()
        } else {
            trackTitleView.text = item.title
            trackArtistView.text = if (item.isStream) "🔴 LIVE • ${item.artist}" else item.artist
            playPauseBtn.text = when {
                service.isPreparing -> "ЗАГРУЗКА..."
                service.isPlaying() -> "PAUSE"
                else -> "PLAY"
            }
            if (!service.isPlaying() && !service.isPreparing) {
                spectrumView.decayToZero()
            }
        }

        trackAdapter.setPlayingUri(item?.uri)
        streamAdapter.setPlayingUri(item?.uri)
    }

    private fun syncDspUiFromEngine() {
        val engine = playbackService?.dspEngine ?: return
        updatingUiProgrammatically = true

        aiSwitch.isChecked = engine.aiEqEnabled
        limiterSwitch.isChecked = engine.antiClipEnabled
        bassSeekBar.progress = engine.bassStrength.coerceIn(0, 1000)
        bassValueView.text = "${engine.bassStrength / 10}%"

        val bands = engine.getActiveBandsDb()
        for (i in 0 until AudioDspEngine.BAND_COUNT) {
            val db = bands[i].coerceIn(AudioDspEngine.MIN_DB, AudioDspEngine.MAX_DB)
            bandLabelViews[i].text = engine.getBandLabel(i)
            bandSeekBars[i].progress = ((db - AudioDspEngine.MIN_DB) * 10f).roundToInt().coerceIn(0, 240)
            bandValueViews[i].text = formatDb(db)
        }

        for ((presetId, btn) in presetButtons) {
            val active = presetId == engine.activePresetId && !engine.aiEqEnabled
            if (active) {
                btn.setTextColor(Color.parseColor("#0A0B10"))
                btn.background = pillDrawable("#00F0FF", "#00F0FF", 12)
            } else {
                btn.setTextColor(Color.parseColor("#C8D2EC"))
                btn.background = pillDrawable("#1B2033", "#2D3654", 12)
            }
        }

        dspStatusView.text = engine.getStatusSummary()
        updatingUiProgrammatically = false
    }

    private fun buildPresetButtonsIfNeeded(engine: AudioDspEngine) {
        if (presetButtons.isNotEmpty()) return
        presetRow.removeAllViews()
        for (preset in engine.presets) {
            val btn = Button(this).apply {
                text = preset.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(42)
                ).apply { rightMargin = dp(8) }
                setOnClickListener { playbackService?.dspEngine?.applyPreset(preset.id) }
            }
            presetButtons[preset.id] = btn
            presetRow.addView(btn)
        }
    }

    // ==================== ПОСТРОЕНИЕ КАРКАСА UI ====================

    private fun buildUi(): View {
        val mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0B10"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Верхняя панель с 3 вкладками
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#0E1019"))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandTitle = TextView(this).apply {
            text = "KOLIR PLAYER"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F0FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(this).apply {
            text = "CORE v1.0"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF2A85"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pillDrawable("#241326", "#FF2A85", 14)
        }
        headerRow.addView(brandTitle)
        headerRow.addView(badge)
        topBar.addView(headerRow)

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        tabPlayerBtn = Button(this).apply {
            text = "ПЛЕЕР"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) }
            setOnClickListener { switchTab(0) }
        }
        tabTracksBtn = Button(this).apply {
            text = "ТРЕКИ (0)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
            setOnClickListener {
                switchTab(1)
                if (allTracks.isEmpty() && hasStoragePermission()) loadDeviceTracks()
            }
        }
        tabRadioBtn = Button(this).apply {
            text = "РАДИО (${streams.size})"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) }
            setOnClickListener { switchTab(2) }
        }
        tabsRow.addView(tabPlayerBtn)
        tabsRow.addView(tabTracksBtn)
        tabsRow.addView(tabRadioBtn)
        topBar.addView(tabsRow)
        mainRoot.addView(topBar)

        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        playerTabContainer = buildPlayerTab()
        tracksTabContainer = buildTracksTab()
        radioTabContainer = buildRadioTab()

        contentHost.addView(playerTabContainer)
        contentHost.addView(tracksTabContainer)
        contentHost.addView(radioTabContainer)
        mainRoot.addView(contentHost)

        return mainRoot
    }

    private fun buildPlayerTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
        }

        spectrumView = NeonSpectrumView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            ).apply { bottomMargin = dp(12) }
            background = cardDrawable("#111420", "#1E2740", 16)
        }
        root.addView(spectrumView)

        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardDrawable("#141724", "#222A42", 16)
        }

        trackTitleView = TextView(this).apply {
            text = "Выберите трек или радиопоток"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        playerCard.addView(trackTitleView)

        trackArtistView = TextView(this).apply {
            text = "KOLIR Core v1.0 • Фон и шторка активны"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94B0"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(4))
        }
        playerCard.addView(trackArtistView)

        dspStatusView = TextView(this).apply {
            text = "Подключение к DSP..."
            textSize = 12f
            setTextColor(Color.parseColor("#7FE9FF"))
            setPadding(0, dp(2), 0, dp(8))
        }
        playerCard.addView(dspStatusView)

        progressBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && playbackService?.currentItem?.isStream != true) {
                        timeCurrentView.text = formatTime(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isSeeking = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeeking = false
                    playbackService?.seekTo(sb?.progress ?: 0)
                }
            })
        }
        playerCard.addView(progressBar)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        timeCurrentView = TextView(this).apply {
            text = "0:00"
            textSize = 11f
            setTextColor(Color.parseColor("#8A94B0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeTotalView = TextView(this).apply {
            text = "0:00"
            textSize = 11f
            setTextColor(Color.parseColor("#8A94B0"))
        }
        timeRow.addView(timeCurrentView)
        timeRow.addView(timeTotalView)
        playerCard.addView(timeRow)

        // Транспорт: Пред / Play-Pause / След
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val prevBtn = Button(this).apply {
            text = "⏮"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pillDrawable("#1D2338", "#344166", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }
            setOnClickListener { playbackService?.playPrevious() }
        }
        playPauseBtn = Button(this).apply {
            text = "PLAY"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = pillDrawable("#FF2A85", "#FF2A85", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 2f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
            setOnClickListener { handlePlayPauseClick() }
        }
        val nextBtn = Button(this).apply {
            text = "⏭"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pillDrawable("#1D2338", "#344166", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) }
            setOnClickListener { playbackService?.playNext() }
        }
        transportRow.addView(prevBtn)
        transportRow.addView(playPauseBtn)
        transportRow.addView(nextBtn)
        playerCard.addView(transportRow)

        // Вспомогательные кнопки: ручной файл + полный стоп фона
        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val manualPickBtn = Button(this).apply {
            text = "ОТКРЫТЬ ФАЙЛ"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F0FF"))
            background = pillDrawable("#101626", "#1F3950", 10)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 2f).apply { rightMargin = dp(6) }
            setOnClickListener { openAudioPicker() }
        }
        val stopBgBtn = Button(this).apply {
            text = "СТОП ФОН"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF80A8"))
            background = pillDrawable("#1F1220", "#4A2034", 10)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { playbackService?.stopPlaybackAndForeground() }
        }
        utilityRow.addView(manualPickBtn)
        utilityRow.addView(stopBgBtn)
        playerCard.addView(utilityRow)
        root.addView(playerCard)

        // Режимы DSP
        val smartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardDrawable("#141724", "#222A42", 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        aiSwitch = Switch(this).apply {
            text = "AI Smart EQ 2.0 (плавный автобаланс)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                if (!updatingUiProgrammatically) {
                    playbackService?.dspEngine?.setAiEqEnabled(checked)
                }
            }
        }
        smartCard.addView(aiSwitch)

        limiterSwitch = Switch(this).apply {
            text = "Anti-Clip Лимитер (защита от хрипа)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, checked ->
                if (!updatingUiProgrammatically) {
                    playbackService?.dspEngine?.setAntiClipEnabled(checked)
                }
            }
        }
        smartCard.addView(limiterSwitch)
        root.addView(smartCard)

        // Пресеты
        val presetHeader = TextView(this).apply {
            text = "КЛУБНЫЕ ПРЕСЕТЫ"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8A94B0"))
            setPadding(dp(4), dp(14), 0, dp(8))
        }
        root.addView(presetHeader)

        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presetScroll.addView(presetRow)
        root.addView(presetScroll)

        // Bass Boost + 5-полосный EQ
        val eqCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardDrawable("#141724", "#222A42", 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val bassHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val bassTitle = TextView(this).apply {
            text = "BASS BOOST"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF2A85"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bassValueView = TextView(this).apply {
            text = "65%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        bassHeaderRow.addView(bassTitle)
        bassHeaderRow.addView(bassValueView)
        eqCard.addView(bassHeaderRow)

        bassSeekBar = SeekBar(this).apply {
            max = 1000
            setPadding(dp(8), dp(6), dp(8), dp(14))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updatingUiProgrammatically) {
                        bassValueView.text = "${progress / 10}%"
                        playbackService?.dspEngine?.setBassStrength(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        eqCard.addView(bassSeekBar)

        for (i in 0 until AudioDspEngine.BAND_COUNT) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            val labelView = TextView(this).apply {
                text = "Полоса ${i + 1}"
                textSize = 12f
                setTextColor(Color.parseColor("#B5C0DC"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valView = TextView(this).apply {
                text = "0.0 dB"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#00F0FF"))
            }
            row.addView(labelView)
            row.addView(valView)
            eqCard.addView(row)

            val sb = SeekBar(this).apply {
                max = 240
                progress = 120
                setPadding(dp(8), dp(2), dp(8), dp(8))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && !updatingUiProgrammatically) {
                            val db = (progress - 120) / 10f
                            valView.text = formatDb(db)
                            playbackService?.dspEngine?.setBandDb(i, db)
                        }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            eqCard.addView(sb)
            bandLabelViews.add(labelView)
            bandValueViews.add(valView)
            bandSeekBars.add(sb)
        }

        root.addView(eqCard)
        scroll.addView(root)
        return scroll
    }

    private fun buildTracksTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val searchInput = EditText(this).apply {
            hint = "Поиск трека или артиста..."
            setHintTextColor(Color.parseColor("#6D7896"))
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardDrawable("#141724", "#273152", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString() ?: ""
                    applyTrackFilter()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val rescanBtn = Button(this).apply {
            text = "ОБНОВИТЬ"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0A0B10"))
            background = pillDrawable("#00F0FF", "#00F0FF", 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                if (hasStoragePermission()) loadDeviceTracks() else requestNeededPermissions()
            }
        }
        searchRow.addView(searchInput)
        searchRow.addView(rescanBtn)
        container.addView(searchRow)

        libraryStatusView = TextView(this).apply {
            text = "Нажмите «ОБНОВИТЬ» или выдайте доступ к музыке"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94B0"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        container.addView(libraryStatusView)

        trackAdapter = MediaListAdapter()
        val listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = trackAdapter
            setOnItemClickListener { _, _, position, _ ->
                if (position in filteredTracks.indices) {
                    playbackService?.playQueue(filteredTracks, position)
                    switchTab(0)
                }
            }
        }
        container.addView(listView)
        return container
    }

    private fun buildRadioTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val addCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardDrawable("#141724", "#222A42", 14)
        }

        val titleInput = EditText(this).apply {
            hint = "Название станции или сета (напр. Kiss FM)"
            setHintTextColor(Color.parseColor("#6D7896"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = cardDrawable("#0E111C", "#273152", 10)
        }
        addCard.addView(titleInput)

        val urlInput = EditText(this).apply {
            hint = "Прямая ссылка потока (http:// или https://...)"
            setHintTextColor(Color.parseColor("#6D7896"))
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = cardDrawable("#0E111C", "#273152", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        addCard.addView(urlInput)

        val addBtn = Button(this).apply {
            text = "+ ДОБАВИТЬ ОНЛАЙН-ПОТОК"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0A0B10"))
            background = pillDrawable("#00F0FF", "#00F0FF", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ссылка должна начинаться с http:// или https://",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val title = titleInput.text.toString().trim()
                streams = mediaRepository.addStream(title, url)
                titleInput.setText("")
                urlInput.setText("")
                updateRadioUi()
            }
        }
        addCard.addView(addBtn)
        container.addView(addCard)

        radioStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A94B0"))
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        container.addView(radioStatusView)

        streamAdapter = MediaListAdapter()
        val listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = streamAdapter
            setOnItemClickListener { _, _, position, _ ->
                if (position in streams.indices) {
                    playbackService?.playQueue(streams, position)
                    switchTab(0)
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (position in streams.indices) {
                    val item = streams[position]
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Удалить поток?")
                        .setMessage(item.title)
                        .setPositiveButton("Удалить") { _, _ ->
                            streams = mediaRepository.removeStream(item.id)
                            updateRadioUi()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
                true
            }
        }
        container.addView(listView)
        return container
    }

    private fun switchTab(tabIndex: Int) {
        playerTabContainer.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        tracksTabContainer.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        radioTabContainer.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        val buttons = listOf(tabPlayerBtn, tabTracksBtn, tabRadioBtn)
        buttons.forEachIndexed { idx, btn ->
            if (idx == tabIndex) {
                btn.setTextColor(Color.parseColor("#0A0B10"))
                btn.background = pillDrawable("#00F0FF", "#00F0FF", 10)
            } else {
                btn.setTextColor(Color.parseColor("#C8D2EC"))
                btn.background = pillDrawable("#1B2033", "#2D3654", 10)
            }
        }
    }

    // ==================== ЗАГРУЗКА ДАННЫХ И ДЕЙСТВИЯ ====================

    private fun loadDeviceTracks() {
        libraryStatusView.text = "Сканирование музыки на телефоне..."
        Thread {
            val scanned = mediaRepository.scanDeviceTracks()
            uiHandler.post {
                allTracks = scanned
                tabTracksBtn.text = "ТРЕКИ (${allTracks.size})"
                applyTrackFilter()
            }
        }.start()
    }

    private fun applyTrackFilter() {
        filteredTracks = mediaRepository.filterItems(allTracks, searchQuery)
        trackAdapter.submitList(filteredTracks)
        libraryStatusView.text = if (allTracks.isEmpty()) {
            "Треки не найдены (можно открыть файл вручную во вкладке ПЛЕЕР)"
        } else {
            "Показано: ${filteredTracks.size} из ${allTracks.size} треков"
        }
    }

    private fun updateRadioUi() {
        tabRadioBtn.text = "РАДИО (${streams.size})"
        streamAdapter.submitList(streams)
        radioStatusView.text = if (streams.isEmpty()) {
            "Потоков пока нет. Вставьте прямую ссылку радиостанции или микса выше."
        } else {
            "Сохранённые потоки (${streams.size}) • Долгое нажатие удаляет"
        }
    }

    private fun handlePlayPauseClick() {
        val service = playbackService ?: return
        if (service.currentItem == null) {
            when {
                filteredTracks.isNotEmpty() -> service.playQueue(filteredTracks, 0)
                streams.isNotEmpty() -> service.playQueue(streams, 0)
                else -> openAudioPicker()
            }
        } else {
            service.togglePlayPause()
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQ_AUDIO_FILE)
    }

    @Deprecated("Standard Activity result handler")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUDIO_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
            val item = mediaRepository.createLocalItemFromUri(uri)
            playbackService?.playSingle(item)
            switchTab(0)
        }
    }

    // ==================== АДАПТЕР СПИСКОВ ====================

    private inner class MediaListAdapter : BaseAdapter() {
        private var items: List<MediaItem> = emptyList()
        private var playingUri: Uri? = null

        fun submitList(next: List<MediaItem>) {
            items = next
            notifyDataSetChanged()
        }

        fun setPlayingUri(uri: Uri?) {
            playingUri = uri
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val isCurrent = item.uri == playingUri

            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val titleTv = TextView(this@MainActivity).apply {
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val artistTv = TextView(this@MainActivity).apply {
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(titleTv)
                textCol.addView(artistTv)

                val durTv = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setPadding(dp(10), 0, 0, 0)
                }
                addView(textCol)
                addView(durTv)
                tag = Triple(titleTv, artistTv, durTv)
            }

            @Suppress("UNCHECKED_CAST")
            val (titleTv, artistTv, durTv) = row.tag as Triple<TextView, TextView, TextView>
            titleTv.text = if (isCurrent) "▶ ${item.title}" else item.title
            titleTv.setTextColor(if (isCurrent) Color.parseColor("#00F0FF") else Color.WHITE)
            artistTv.text = item.artist
            artistTv.setTextColor(Color.parseColor("#8A94B0"))
            durTv.text = if (item.isStream) "LIVE" else formatTime(item.durationMs)
            durTv.setTextColor(
                if (isCurrent || item.isStream) Color.parseColor("#FF2A85")
                else Color.parseColor("#8A94B0")
            )

            row.background = if (isCurrent) {
                cardDrawable("#192238", "#00F0FF", 12)
            } else {
                cardDrawable("#141724", "#20273D", 12)
            }
            return row
        }
    }

    // ==================== ПРАВА И УТИЛИТЫ ====================

    private fun storagePermissionName(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            PERM_READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return checkSelfPermission(storagePermissionName()) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val toRequest = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!hasStoragePermission()) {
            toRequest.add(storagePermissionName())
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(PERM_POST_NOTIFICATIONS)
        }
        if (toRequest.isNotEmpty()) {
            requestPermissions(toRequest.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            playbackService?.dspEngine?.refreshVisualizerPermission()
            if (hasStoragePermission()) {
                loadDeviceTracks()
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun formatDb(db: Float): String {
        val sign = if (db > 0.05f) "+" else ""
        return String.format(Locale.US, "%s%.1f dB", sign, db)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun cardDrawable(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun pillDrawable(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    // ==================== НЕОНОВЫЙ ВИЗУАЛИЗАТОР ====================

    private class NeonSpectrumView(context: Context) : View(context) {
        private val bars = FloatArray(AudioDspEngine.SPECTRUM_BARS) { 0.05f }
        private val rect = RectF()
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C2338")
            strokeWidth = 2f
        }

        fun updateBars(next: FloatArray) {
            val count = min(bars.size, next.size)
            for (i in 0 until count) {
                val target = next[i].coerceIn(0.04f, 1f)
                bars[i] = if (target > bars[i]) {
                    bars[i] * 0.55f + target * 0.45f
                } else {
                    bars[i] * 0.84f + target * 0.16f
                }
            }
            postInvalidate()
        }

        fun decayToZero() {
            for (i in bars.indices) bars[i] = 0.04f
            postInvalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            barPaint.shader = LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                intArrayOf(
                    Color.parseColor("#00F0FF"),
                    Color.parseColor("#7B2CFF"),
                    Color.parseColor("#FF2A85")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 24f
            val availW = width - pad * 2f
            val availH = height - pad * 2f
            if (availW <= 0f || availH <= 0f) return

            canvas.drawLine(pad, height * 0.33f, width - pad, height * 0.33f, gridPaint)
            canvas.drawLine(pad, height * 0.66f, width - pad, height * 0.66f, gridPaint)

            val step = availW / bars.size
            val barW = step * 0.66f
            for (i in bars.indices) {
                val barH = (bars[i] * availH).coerceAtLeast(8f)
                val left = pad + i * step + (step - barW) * 0.5f
                val top = height - pad - barH
                rect.set(left, top, left + barW, height - pad)
                canvas.drawRoundRect(rect, 8f, 8f, barPaint)
            }
        }
    }
}
