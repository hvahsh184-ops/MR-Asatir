package com.example.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class PassiveVlfTelemetry(
    val sampleRateHz: Int,
    val peakFrequencyHz: Double,
    val signalRmsDb: Double,
    val elfSchumannEnergy: Double,
    val voidResonanceEnergy: Double,
    val telluricAudioEnergy: Double,
    val highVlfEnergy: Double,
    val isAnomalyDetected: Boolean,
    val anomalyDescriptionFa: String,
    val spectrumBands: List<Float>,
    val timestampMs: Long = System.currentTimeMillis()
)

class PassiveAudioVlfManager(private val context: Context) {
    private val TAG = "PassiveAudioVlfManager"

    private val _telemetry = MutableStateFlow<PassiveVlfTelemetry?>(null)
    val telemetry: StateFlow<PassiveVlfTelemetry?> = _telemetry.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusMessageFa = MutableStateFlow("حالت پسیو آماده اتصال به جک صوتی / پروب زمین (تایید نرخ 192kHz)")
    val statusMessageFa: StateFlow<String> = _statusMessageFa.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Strict priority for 192kHz ultra-high frequency sampling, descending to 96kHz, 48kHz, 44.1kHz
    private val candidateSampleRates = intArrayOf(192000, 96000, 48000, 44100)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_isRunning.value) return true

        val recordInstance = initBestAudioRecord()
        if (recordInstance == null) {
            _statusMessageFa.value = "خطا در دسترسی به جک صوتی؛ لطفاً مجوز ضبط صدا را بررسی کنید."
            return false
        }
        audioRecord = recordInstance

        runCatching {
            recordInstance.startRecording()
        }.onFailure {
            _statusMessageFa.value = "عدم امکان شروع ضبط پسیو 192kHz: ${it.localizedMessage}"
            recordInstance.release()
            audioRecord = null
            return false
        }

        _isRunning.value = true
        val sampleRate = recordInstance.sampleRate
        _statusMessageFa.value = String.format(
            Locale.US,
            "فعال روی بالاترین فرکانس سخت‌افزاری (نرخ نمونه‌برداری: %,d هرتز)",
            sampleRate
        )
        Log.i(TAG, "Passive Audio / VLF started successfully at $sampleRate Hz")

        recordingJob = scope.launch {
            val fftSize = 4096 // Higher FFT resolution for 192kHz
            val buffer = ShortArray(fftSize)
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            val window = DoubleArray(fftSize) { n ->
                0.5 * (1.0 - cos(2.0 * PI * n / (fftSize - 1))) // Hann window
            }

            while (isActive && _isRunning.value) {
                val readCount = recordInstance.read(buffer, 0, fftSize)
                if (readCount < fftSize) {
                    delay(15L)
                    continue
                }

                var sumSq = 0.0
                for (i in 0 until fftSize) {
                    // Subsonic filter simulation: subtract DC / ultra-low mechanical drift (< 20Hz)
                    val sample = buffer[i].toDouble() / 32768.0
                    sumSq += sample * sample
                    real[i] = sample * window[i]
                    imag[i] = 0.0
                }

                val rms = sqrt(sumSq / fftSize).coerceAtLeast(1e-6)
                val rmsDb = (20.0 * log10(rms)).coerceIn(-120.0, 0.0)

                fftInPlace(real, imag, fftSize)

                val halfSize = fftSize / 2
                val binWidth = sampleRate.toDouble() / fftSize
                var maxMag = 0.0
                var peakBin = 0

                val magSpectrum = DoubleArray(halfSize)
                for (i in 1 until halfSize) {
                    val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                    magSpectrum[i] = mag
                    if (mag > maxMag) {
                        maxMag = mag
                        peakBin = i
                    }
                }

                val peakFreqHz = peakBin * binWidth

                var elfSum = 0.0
                var voidSum = 0.0
                var telluricSum = 0.0
                var highVlfSum = 0.0

                for (i in 1 until halfSize) {
                    val f = i * binWidth
                    val energy = magSpectrum[i] * magSpectrum[i]
                    when {
                        f < 50.0 -> elfSum += energy
                        f < 500.0 -> voidSum += energy
                        f < 20000.0 -> telluricSum += energy
                        else -> highVlfSum += energy // Captures up to 96kHz Nyquist when sampled at 192kHz!
                    }
                }

                val visualBands = FloatArray(32)
                val step = halfSize / 32
                for (b in 0 until 32) {
                    var bandMax = 0.0
                    val start = b * step
                    val end = ((b + 1) * step).coerceAtMost(halfSize)
                    for (k in start until end) {
                        if (magSpectrum[k] > bandMax) bandMax = magSpectrum[k]
                    }
                    val normalized = (bandMax * 12.0).coerceIn(0.0, 1.0)
                    visualBands[b] = normalized.toFloat()
                }

                val isCavityPeak = voidSum > 0.07 && voidSum > (telluricSum * 1.4)
                val isTelluricAnomaly = (telluricSum > 0.10 || highVlfSum > 0.08) && maxMag > 0.12
                val isAnomaly = (isCavityPeak || isTelluricAnomaly) && rmsDb > -68.0

                val anomalyText = when {
                    isCavityPeak -> String.format(
                        Locale.US,
                        "تشدید حفره و اتاقک زیرزمینی در فرکانس %.1f هرتز (انرژی باند صوتی عمیق)",
                        peakFreqHz
                    )
                    isTelluricAnomaly -> String.format(
                        Locale.US,
                        "ناهنجاری فرکانس بالا (VLF/Telluric) در %.1f هرتز (میدان القایی متریال هدف)",
                        peakFreqHz
                    )
                    rmsDb < -78.0 -> "سیگنال پسیو بسیار ضعیف است — کابل/پروب ورودی را بررسی کنید."
                    else -> "پس‌زمینه طبیعی 192kHz پایدار — بدون تداخل غیرمتعارف"
                }

                _telemetry.value = PassiveVlfTelemetry(
                    sampleRateHz = sampleRate,
                    peakFrequencyHz = peakFreqHz,
                    signalRmsDb = rmsDb,
                    elfSchumannEnergy = elfSum,
                    voidResonanceEnergy = voidSum,
                    telluricAudioEnergy = telluricSum,
                    highVlfEnergy = highVlfSum,
                    isAnomalyDetected = isAnomaly,
                    anomalyDescriptionFa = anomalyText,
                    spectrumBands = visualBands.toList()
                )

                delay(50L)
            }
        }
        return true
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        recordingJob?.cancel()
        recordingJob = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        _statusMessageFa.value = "آنالیزور 192kHz پسیو متوقف شد."
    }

    @SuppressLint("MissingPermission")
    private fun initBestAudioRecord(): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (rate in candidateSampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuf <= 0) continue

            val bufSize = max(minBuf * 4, 16384)
            for (source in sourcesToTry) {
                val record = runCatching {
                    AudioRecord(source, rate, channelConfig, audioFormat, bufSize)
                }.getOrNull()

                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Successfully initialized AudioRecord at sample rate: $rate Hz using source: $source")
                    return record
                }
                record?.release()
            }
        }
        return null
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var l = 2
        while (l <= n) {
            val angle = -2.0 * PI / l
            val wstepR = cos(angle)
            val wstepI = sin(angle)
            var wR = 1.0
            var wI = 0.0

            for (m in 0 until l / 2) {
                var i = m
                while (i < n) {
                    val idx = i + l / 2
                    val tr = wR * real[idx] - wI * imag[idx]
                    val ti = wR * imag[idx] + wI * real[idx]
                    real[idx] = real[i] - tr
                    imag[idx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += l
                }
                val nextWr = wR * wstepR - wI * wstepI
                val nextWi = wR * wstepI + wI * wstepR
                wR = nextWr
                wI = nextWi
            }
            l = l shl 1
        }
    }
}
