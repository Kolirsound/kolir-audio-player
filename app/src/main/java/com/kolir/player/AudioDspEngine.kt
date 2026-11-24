package com.kolir.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AudioDspEngine(context: Context) {

    companion object {
        private const val PREFS_NAME = "kolir_dsp_core_prefs"
        const val BAND_COUNT = 5
        const val MIN_DB = -12f
        const val MAX_DB = 12f
        const val SPECTRUM_BARS = 24
    }

    data class Preset(
        val id: String,
        val title: String,
        val bandsDb: FloatArray,
        val bassStrength: Int
    )

    val presets: List<Preset> = listOf(
        Preset("club128", "CLUB 128", floatArrayOf(5.0f, 2.0f, -1.0f, 2.5f, 4.0f), 650),
        Preset("deepbass", "DEEP BASS", floatArrayOf(7.5f, 3.5f, -1.5f, 1.0f, 2.0f), 880),
        Preset("ethnovocal", "ETHNO VOCAL", floatArrayOf(2.5f, 1.0f, 3.5f, 4.0f, 2.5f), 350),
        Preset("nightdrive", "NIGHT DRIVE", floatArrayOf(4.5f, 1.5f, 0.0f, 1.5f, 3.0f), 550),
        Preset("flat", "FLAT", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f), 0)
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = 0

    private val userBandsDb = FloatArray(BAND_COUNT) { 0f }
    private val aiBandsDb = FloatArray(BAND_COUNT) { 0f }
    private val smoothEnergy = FloatArray(BAND_COUNT) { 0f }
    private val bandLabels = arrayOf(
        "60 Гц (Суб)",
        "230 Гц (Бас)",
        "910 Гц (Вокал)",
        "3.6 кГц (Присут.)",
        "14 кГц (Верх)"
    )

    var bassStrength: Int = 650
        private set
    var activePresetId: String = "club128"
        private set
    var aiEqEnabled: Boolean = false
        private set
    var antiClipEnabled: Boolean = true
        private set
    var lastHeadroomCutDb: Float = 0f
        private set

    private var lastAiApplyTime: Long = 0L

    var onSpectrumUpdated: ((FloatArray) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    init {
        loadPreferences()
    }

    fun attachToSession(sessionId: Int) {
        releaseEffects()
        if (sessionId == 0) return
        currentSessionId = sessionId

        // 1. 5-полосный эквалайзер
        try {
            val eq = Equalizer(0, sessionId).apply { enabled = true }
            equalizer = eq
            val bands = min(BAND_COUNT, eq.numberOfBands.toInt())
            for (i in 0 until bands) {
                val freqHz = eq.getCenterFreq(i.toShort()) / 1000
                bandLabels[i] = if (freqHz >= 1000) {
                    String.format(Locale.US, "%.1f кГц", freqHz / 1000f)
                } else {
                    "$freqHz Гц"
                }
            }
        } catch (_: Throwable) {
            equalizer = null
        }

        // 2. Bass Boost
        try {
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
        } catch (_: Throwable) {
            bassBoost = null
        }

        // 3. Аппаратный Anti-Clip лимитер (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cfgBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    false, 0,
                    false, 0,
                    false, 0,
                    true
                )
                val limiter = DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    2.0f,
                    60.0f,
                    10.0f,
                    -1.5f,
                    0.0f
                )
                cfgBuilder.setLimiterAllChannelsTo(limiter)
                dynamicsProcessing = DynamicsProcessing(0, sessionId, cfgBuilder.build()).apply {
                    enabled = antiClipEnabled
                }
            } catch (_: Throwable) {
                dynamicsProcessing = null
            }
        }

        applyAllEffects()
        attachVisualizer(sessionId)
        notifyStateChanged()
    }

    fun refreshVisualizerPermission() {
        if (visualizer == null && currentSessionId != 0) {
            attachVisualizer(currentSessionId)
        }
    }

    fun applyPreset(presetId: String) {
        val preset = presets.firstOrNull { it.id == presetId } ?: return
        activePresetId = preset.id
        for (i in 0 until BAND_COUNT) {
            userBandsDb[i] = preset.bandsDb[i]
            aiBandsDb[i] = preset.bandsDb[i]
        }
        bassStrength = preset.bassStrength
        aiEqEnabled = false
        applyAllEffects()
        savePreferences()
        notifyStateChanged()
    }

    fun setBandDb(bandIndex: Int, db: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        userBandsDb[bandIndex] = db.coerceIn(MIN_DB, MAX_DB)
        activePresetId = "custom"
        aiEqEnabled = false
        applyAllEffects()
        savePreferences()
        notifyStateChanged()
    }

    fun setBassStrength(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        activePresetId = "custom"
        applyAllEffects()
        savePreferences()
        notifyStateChanged()
    }

    fun setAiEqEnabled(enabled: Boolean) {
        aiEqEnabled = enabled
        if (enabled) {
            for (i in 0 until BAND_COUNT) aiBandsDb[i] = userBandsDb[i]
            smoothEnergy.fill(0f)
        }
        applyAllEffects()
        savePreferences()
        notifyStateChanged()
    }

    fun setAntiClipEnabled(enabled: Boolean) {
        antiClipEnabled = enabled
        applyAllEffects()
        savePreferences()
        notifyStateChanged()
    }

    fun getActiveBandsDb(): FloatArray {
        return if (aiEqEnabled) aiBandsDb.copyOf() else userBandsDb.copyOf()
    }

    fun getBandLabel(index: Int): String {
        return bandLabels.getOrElse(index) { "Полоса ${index + 1}" }
    }

    fun getStatusSummary(): String {
        val modePart = if (aiEqEnabled) {
            "AI EQ: автобаланс"
        } else {
            val pName = presets.firstOrNull { it.id == activePresetId }?.title ?: "CUSTOM"
            "Пресет: $pName"
        }

        val limiterPart = if (antiClipEnabled) {
            if (lastHeadroomCutDb > 0.2f) {
                String.format(Locale.US, "Лимитер: -%.1f dB", lastHeadroomCutDb)
            } else {
                "Лимитер: активен"
            }
        } else {
            "Лимитер: выкл"
        }

        return "$modePart  •  $limiterPart"
    }

    fun releaseEffects() {
        currentSessionId = 0
        try { visualizer?.enabled = false } catch (_: Throwable) {}
        try { visualizer?.release() } catch (_: Throwable) {}
        visualizer = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { dynamicsProcessing?.release() } catch (_: Throwable) {}
        }
        dynamicsProcessing = null

        try { bassBoost?.release() } catch (_: Throwable) {}
        bassBoost = null

        try { equalizer?.release() } catch (_: Throwable) {}
        equalizer = null
    }

    private fun applyAllEffects() {
        val activeBands = if (aiEqEnabled) aiBandsDb else userBandsDb

        var maxBand = 0f
        for (db in activeBands) {
            if (db > maxBand) maxBand = db
        }
        val bassExtraDb = (bassStrength / 1000f) * 5.5f
        val effectivePeak = max(maxBand, activeBands[0] + bassExtraDb * 0.7f)

        lastHeadroomCutDb = if (antiClipEnabled && effectivePeak > 3.5f) {
            min(6.5f, (effectivePeak - 3.5f) * 0.65f)
        } else {
            0f
        }

        equalizer?.let { eq ->
            try {
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                val count = min(BAND_COUNT, eq.numberOfBands.toInt())
                for (i in 0 until count) {
                    val compensatedDb = activeBands[i] - lastHeadroomCutDb
                    val targetMb = (compensatedDb * 100f).roundToInt().coerceIn(minMb, maxMb)
                    eq.setBandLevel(i.toShort(), targetMb.toShort())
                }
            } catch (_: Throwable) {
            }
        }

        bassBoost?.let { bb ->
            try {
                val safeStrength = if (antiClipEnabled) min(bassStrength, 920) else bassStrength
                bb.setStrength(safeStrength.coerceIn(0, 1000).toShort())
            } catch (_: Throwable) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.enabled = antiClipEnabled
            } catch (_: Throwable) {
            }
        }
    }

    private fun attachVisualizer(sessionId: Int) {
        if (!hasRecordAudioPermission()) return
        try {
            val sizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = min(1024, sizeRange[1])
            val vis = Visualizer(sessionId).apply {
                this.captureSize = captureSize
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) processFftFrame(fft)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            visualizer = vis
        } catch (_: Throwable) {
            visualizer = null
        }
    }

    private fun processFftFrame(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < 32) return

        val visualBars = FloatArray(SPECTRUM_BARS)
        for (bar in 0 until SPECTRUM_BARS) {
            val startBin = max(1, (bar * bins) / (SPECTRUM_BARS * 2))
            val endBin = max(startBin + 1, ((bar + 1) * bins) / (SPECTRUM_BARS * 2))
            var sum = 0f
            for (k in startBin until min(endBin, bins)) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += hypot(re, im)
            }
            val avg = sum / (endBin - startBin)
            visualBars[bar] = (ln(1f + avg) / 4.8f).coerceIn(0f, 1f)
        }
        onSpectrumUpdated?.invoke(visualBars)

        if (!aiEqEnabled) return

        val zones = arrayOf(
            1 to 4,
            4 to 12,
            12 to 36,
            36 to 96,
            96 to min(220, bins)
        )

        var totalEnergy = 0f
        val frameEnergy = FloatArray(BAND_COUNT)
        for (z in 0 until BAND_COUNT) {
            val from = zones[z].first
            val to = max(from + 1, zones[z].second)
            var sum = 0f
            for (k in from until min(to, bins)) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += hypot(re, im)
            }
            val level = (ln(1f + sum / (to - from)) / 4.5f).coerceIn(0f, 1f)
            frameEnergy[z] = level
            totalEnergy += level
        }

        if (totalEnergy < 0.35f) return

        val emaAlpha = 0.045f
        for (i in 0 until BAND_COUNT) {
            smoothEnergy[i] = if (smoothEnergy[i] == 0f) {
                frameEnergy[i]
            } else {
                smoothEnergy[i] * (1f - emaAlpha) + frameEnergy[i] * emaAlpha
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastAiApplyTime < 350L) return
        lastAiApplyTime = now

        val targetProfile = floatArrayOf(0.72f, 0.58f, 0.48f, 0.54f, 0.62f)
        val avgEnergy = smoothEnergy.sum() / BAND_COUNT
        if (avgEnergy < 0.05f) return

        for (i in 0 until BAND_COUNT) {
            val relativeEnergy = (smoothEnergy[i] / avgEnergy) * 0.55f
            val desiredDb = ((targetProfile[i] - relativeEnergy) * 14f).coerceIn(-4.5f, 6.5f)
            val delta = (desiredDb - aiBandsDb[i]).coerceIn(-0.45f, 0.45f)
            aiBandsDb[i] = (aiBandsDb[i] + delta).coerceIn(MIN_DB, MAX_DB)
        }

        mainHandler.post {
            if (!aiEqEnabled) return@post
            applyAllEffects()
            notifyStateChanged()
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun notifyStateChanged() {
        mainHandler.post { onStateChanged?.invoke() }
    }

    private fun savePreferences() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("preset_id", activePresetId)
            putInt("bass_strength", bassStrength)
            putBoolean("ai_eq", aiEqEnabled)
            putBoolean("anti_clip", antiClipEnabled)
            for (i in 0 until BAND_COUNT) {
                putFloat("band_$i", userBandsDb[i])
            }
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activePresetId = prefs.getString("preset_id", "club128") ?: "club128"
        bassStrength = prefs.getInt("bass_strength", 650).coerceIn(0, 1000)
        aiEqEnabled = prefs.getBoolean("ai_eq", false)
        antiClipEnabled = prefs.getBoolean("anti_clip", true)

        val defaultPreset = presets.first()
        for (i in 0 until BAND_COUNT) {
            userBandsDb[i] = prefs.getFloat("band_$i", defaultPreset.bandsDb[i]).coerceIn(MIN_DB, MAX_DB)
            aiBandsDb[i] = userBandsDb[i]
        }
    }
}
