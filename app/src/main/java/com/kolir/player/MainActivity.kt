package com.kolir.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val PICK_AUDIO_CODE = 101
        private const val PERM_AUDIO_CODE = 102
    }

    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var visualizer: Visualizer? = null

    private lateinit var spectrumView: NeonSpectrumView
    private lateinit var trackTitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var smartEqBtn: Button
    private lateinit var eqContainer: LinearLayout
    private lateinit var bassSeekBar: SeekBar

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueViews = mutableListOf<TextView>()
    private var minEqLevel: Short = -1500
    private var maxEqLevel: Short = 1500
    private var isSmartAutoEq = false
    private var lastAutoUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_AUDIO_CODE)
        }

        setContentView(buildNeonUi())
    }

    private fun buildNeonUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080812"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }

        val header = TextView(this).apply {
            text = "KOLIR SOUND"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F5D4"))
            gravity = Gravity.CENTER
        }
        root.addView(header)

        val subtitle = TextView(this).apply {
            text = "AUDIO ENGINE • AI SPECTRUM • SMART EQ"
            textSize = 11f
            setTextColor(Color.parseColor("#9B5DE5"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(16))
        }
        root.addView(subtitle)

        spectrumView = NeonSpectrumView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160)
            ).apply { bottomMargin = dp(16) }
            background = makeCardBg("#101024", "#2B2B52")
        }
        root.addView(spectrumView)

        trackTitleView = TextView(this).apply {
            text = "Трек не выбран"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(trackTitleView)

        statusView = TextView(this).apply {
            text = "Выбери MP3/WAV с телефона для запуска движка"
            textSize = 12f
            setTextColor(Color.parseColor("#8A8AA8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(statusView)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val pickBtn = makeNeonButton("ВЫБРАТЬ ТРЕК", "#161632", "#00F5D4").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { openAudioPicker() }
        }

        playPauseBtn = makeNeonButton("PLAY / PAUSE", "#240B36", "#FF007F").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
            setOnClickListener { togglePlayPause() }
        }

        controlsRow.addView(pickBtn)
        controlsRow.addView(playPauseBtn)
        root.addView(controlsRow)

        smartEqBtn = makeNeonButton("AI SMART EQ: ВЫКЛ", "#12122A", "#9B5DE5").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { bottomMargin = dp(14) }
            setOnClickListener { toggleSmartEq() }
        }
        root.addView(smartEqBtn)

        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val presets = listOf(
            "FLAT" to intArrayOf(0, 0, 0, 0, 0),
            "CLUB" to intArrayOf(600, 300, -100, 300, 500),
            "ETHNO" to intArrayOf(400, 100, 450, 350, 200),
            "BASS+" to intArrayOf(900, 500, 0, 100, 300)
        )
        presets.forEachIndexed { idx, (label, values) ->
            val btn = makeNeonButton(label, "#111126", "#3A3A6A").apply {
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                }
                setOnClickListener {
                    isSmartAutoEq = false
                    updateSmartBtnStyle()
                    applyPreset(values)
                    statusView.text = "Пресет: $label"
                }
            }
            presetsRow.addView(btn)
        }
        root.addView(presetsRow)

        val bassCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#101024", "#26264A")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val bassLabel = TextView(this).apply {
            text = "BASS BOOST: 0%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F5D4"))
        }
        bassSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    bassLabel.text = "BASS BOOST: ${progress / 10}%"
                    try {
                        bassBoost?.setStrength(progress.toShort())
                    } catch (_: Exception) {
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bassCard.addView(bassLabel)
        bassCard.addView(bassSeekBar)
        root.addView(bassCard)

        eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#101024", "#26264A")
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val eqPlaceholder = TextView(this).apply {
            text = "Полосы эквалайзера появятся после выбора трека"
            textSize = 13f
            setTextColor(Color.parseColor("#8A8AA8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        eqContainer.addView(eqPlaceholder)
        root.addView(eqContainer)

        scroll.addView(root)
        return scroll
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, PICK_AUDIO_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_AUDIO_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                loadAndPlayTrack(uri)
            }
        }
    }

    private fun loadAndPlayTrack(uri: Uri) {
        releaseAudio()
        try {
            val player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                start()
                setOnCompletionListener {
                    playPauseBtn.text = "PLAY"
                    statusView.text = "Трек завершён"
                }
            }
            mediaPlayer = player
            trackTitleView.text = getFileName(uri)
            playPauseBtn.text = "PAUSE"
            statusView.text = "KolirAudioEngine активен"

            initAudioEffects(player.audioSessionId)
            initAudioAnalyzer(player.audioSessionId)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запуска трека: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initAudioEffects(sessionId: Int) {
        try {
            val eq = Equalizer(0, sessionId).apply { enabled = true }
            equalizer = eq
            val range = eq.bandLevelRange
            minEqLevel = range[0]
            maxEqLevel = range[1]
            buildEqualizerSliders(eq)
        } catch (_: Exception) {
            statusView.text = "Эквалайзер недоступен на этом устройстве"
        }

        try {
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = true
                setStrength(bassSeekBar.progress.toShort())
            }
        } catch (_: Exception) {
        }
    }

    private fun buildEqualizerSliders(eq: Equalizer) {
        eqContainer.removeAllViews()
        bandSeekBars.clear()
        bandValueViews.clear()

        val title = TextView(this).apply {
            text = "KOLIR 5-BAND EQUALIZER"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF007F"))
            setPadding(0, 0, 0, dp(10))
        }
        eqContainer.addView(title)

        val bands = eq.numberOfBands.toInt()
        val totalRange = (maxEqLevel - minEqLevel).coerceAtLeast(1)

        for (i in 0 until bands) {
            val band = i.toShort()
            val freqHz = eq.getCenterFreq(band) / 1000
            val freqText = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"

            val rowHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val freqLabel = TextView(this).apply {
                text = freqText
                textSize = 12f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valLabel = TextView(this).apply {
                text = "0 dB"
                textSize = 12f
                setTextColor(Color.parseColor("#00F5D4"))
            }
            rowHeader.addView(freqLabel)
            rowHeader.addView(valLabel)

            val seekBar = SeekBar(this).apply {
                max = totalRange
                progress = (eq.getBandLevel(band) - minEqLevel).coerceIn(0, totalRange)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val level = (progress + minEqLevel).toShort()
                        valLabel.text = "${level / 100} dB"
                        if (fromUser) {
                            isSmartAutoEq = false
                            updateSmartBtnStyle()
                            try {
                                equalizer?.setBandLevel(band, level)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            bandSeekBars.add(seekBar)
            bandValueViews.add(valLabel)
            eqContainer.addView(rowHeader)
            eqContainer.addView(seekBar)
        }
    }

    private fun initAudioAnalyzer(sessionId: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusView.text = "Разреши микрофон для живого анализатора спектра"
            return
        }
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft != null) {
                            processSpectrum(fft)
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) {
        }
    }

    private fun processSpectrum(fft: ByteArray) {
        if (fft.size < 4) return
        val barCount = 24
        val bars = FloatArray(barCount)
        var lowSum = 0f
        var midSum = 0f
        var highSum = 0f

        for (i in 0 until barCount) {
            val idx = (i * 2 + 2).coerceAtMost(fft.size - 2)
            val re = fft[idx].toFloat()
            val im = fft[idx + 1].toFloat()
            val mag = hypot(re.toDouble(), im.toDouble()).toFloat()
            val norm = (mag / 64f).coerceIn(0.05f, 1f)
            bars[i] = norm
            when {
                i < 6 -> lowSum += norm
                i < 16 -> midSum += norm
                else -> highSum += norm
            }
        }

        runOnUiThread {
            spectrumView.updateBars(bars)
            if (isSmartAutoEq && System.currentTimeMillis() - lastAutoUpdate > 450) {
                lastAutoUpdate = System.currentTimeMillis()
                autoBalanceEq(lowSum / 6f, midSum / 10f, highSum / 8f)
            }
        }
    }

    private fun autoBalanceEq(low: Float, mid: Float, high: Float) {
        val eq = equalizer ?: return
        val bands = eq.numberOfBands.toInt()
        if (bands == 0) return

        val lowGain = ((0.55f - low) * 900f).toInt().coerceIn(-400, 700)
        val midGain = ((0.45f - mid) * 700f).toInt().coerceIn(-300, 500)
        val highGain = ((0.40f - high) * 800f).toInt().coerceIn(-300, 650)

        val target = intArrayOf(lowGain, (lowGain + midGain) / 2, midGain, (midGain + highGain) / 2, highGain)
        applyPreset(target)
    }

    private fun applyPreset(values: IntArray) {
        val eq = equalizer ?: return
        val bands = eq.numberOfBands.toInt()
        for (i in 0 until min(bands, values.size)) {
            val clamped = values[i].coerceIn(minEqLevel.toInt(), maxEqLevel.toInt()).toShort()
            try {
                eq.setBandLevel(i.toShort(), clamped)
                if (i < bandSeekBars.size) {
                    bandSeekBars[i].progress = clamped - minEqLevel
                    bandValueViews[i].text = "${clamped / 100} dB"
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun toggleSmartEq() {
        isSmartAutoEq = !isSmartAutoEq
        updateSmartBtnStyle()
        statusView.text = if (isSmartAutoEq) "AI Smart EQ подстраивает звук в реальном времени" else "Ручной режим эквалайзера"
    }

    private fun updateSmartBtnStyle() {
        if (isSmartAutoEq) {
            smartEqBtn.text = "AI SMART EQ: АКТИВЕН"
            smartEqBtn.background = makeCardBg("#1F0D38", "#00F5D4")
            smartEqBtn.setTextColor(Color.parseColor("#00F5D4"))
        } else {
            smartEqBtn.text = "AI SMART EQ: ВЫКЛ"
            smartEqBtn.background = makeCardBg("#12122A", "#9B5DE5")
            smartEqBtn.setTextColor(Color.WHITE)
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: run {
            openAudioPicker()
            return
        }
        if (mp.isPlaying) {
            mp.pause()
            playPauseBtn.text = "PLAY"
            statusView.text = "Пауза"
        } else {
            mp.start()
            playPauseBtn.text = "PAUSE"
            statusView.text = "KolirAudioEngine активен"
        }
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "KOLIR Track"
    }

    private fun makeNeonButton(label: String, bgHex: String, strokeHex: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = makeCardBg(bgHex, strokeHex)
            isAllCaps = true
        }
    }

    private fun makeCardBg(bgHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun releaseAudio() {
        try { visualizer?.release() } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        visualizer = null
        equalizer = null
        bassBoost = null
        mediaPlayer = null
    }

    override fun onDestroy() {
        releaseAudio()
        super.onDestroy()
    }

    class NeonSpectrumView(context: Context) : View(context) {
        private var bars = FloatArray(24) { 0.12f }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun updateBars(newBars: FloatArray) {
            for (i in bars.indices) {
                val target = if (i < newBars.size) newBars[i] else 0.1f
                bars[i] = max(target, bars[i] * 0.82f)
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            barPaint.shader = LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                intArrayOf(
                    Color.parseColor("#9B5DE5"),
                    Color.parseColor("#00F5D4"),
                    Color.parseColor("#FF007F")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 24f
            val availW = width - pad * 2
            val availH = height - pad * 2
            if (availW <= 0f || availH <= 0f) return

            val step = availW / bars.size
            val barW = step * 0.65f
            for (i in bars.indices) {
                val h = (bars[i] * availH).coerceAtLeast(10f)
                val left = pad + i * step + (step - barW) / 2f
                val top = height - pad - h
                rect.set(left, top, left + barW, height - pad)
                canvas.drawRoundRect(rect, 8f, 8f, barPaint)
            }
        }
    }
}
