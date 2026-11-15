package com.example.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.domain.AudioStftEngine
import com.example.domain.AudioStftResult
import com.example.domain.PassiveMaterialId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

data class AudioPassivePipelineState(
    val isListening: Boolean = false,
    val sampleRateHz: Int = 192000,
    val nyquistHz: Int = 96000,
    val audioSourceLabel: String = "STANDBY",
    val gainMultiplier: Float = 1.0f,
    val enabledMaterials: Set<PassiveMaterialId> = PassiveMaterialId.entries.toSet(),
    val isCalibrating: Boolean = false,
    val calibrationProgress: Float = 0f,
    val isCalibrated: Boolean = false,
    val lastResult: AudioStftResult? = null,
    val waterfallHistory: List<List<Float>> = emptyList(),
    val frameSeq: Long = 0L,
    val dspProcessingMs: Float = 0f,
    val errorMessage: String? = null
)

/**
 * Isolated 192kHz Passive Audio Capture & STFT DSP Pipeline.
 *
 * Architecture guarantees zero main-thread blocking and complete decoupling from the main radar feed:
 * - AudioRecord capture runs exclusively on Dispatchers.IO.
 * - Radix-2 STFT, noise calibration, material band scoring, and depth telemetry run on a dedicated
 *   single-threaded DSP worker (Dispatchers.Default.limitedParallelism(1)).
 * - Frames cross between IO and DSP via a DROP_OLDEST bounded Channel so audio DMA never stalls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioHeadphonePassivePipeline(private val context: Context) {
    private val TAG = "AudioHeadphonePipeline"

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dspDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow(AudioPassivePipelineState())
    val state: StateFlow<AudioPassivePipelineState> = _state.asStateFlow()

    private val stftEngine = AudioStftEngine(fftSize = 4096, initialSampleRateHz = 192000)

    private var captureJob: Job? = null
    private var dspJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val candidateSampleRates = intArrayOf(192000, 96000, 48000, 44100)
    private val waterfallBuffer = ArrayDeque<List<Float>>(24)

    @Volatile
    private var pendingCalibrationFrames = 0
    private val calibrationTargetFrames = 20

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (_state.value.isListening) return

        val frameChannel = Channel<ShortArray>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        dspJob?.cancel()
        dspJob = pipelineScope.launch(dspDispatcher) {
            var lastUiEmitMs = 0L
            for (frame in frameChannel) {
                if (!isActive) break
                val startNs = System.nanoTime()

                val gain = _state.value.gainMultiplier
                if (gain != 1.0f) {
                    for (i in frame.indices) {
                        val amplified = (frame[i] * gain).toInt().coerceIn(-32768, 32767)
                        frame[i] = amplified.toShort()
                    }
                }

                if (pendingCalibrationFrames > 0) {
                    stftEngine.accumulateCalibrationFrame(frame, 0, frame.size)
                    pendingCalibrationFrames--
                    val doneCount = calibrationTargetFrames - pendingCalibrationFrames
                    val progress = (doneCount.toFloat() / calibrationTargetFrames).coerceIn(0f, 1f)
                    if (pendingCalibrationFrames == 0) {
                        val ok = stftEngine.finalizeCalibration()
                        _state.update {
                            it.copy(
                                isCalibrating = false,
                                calibrationProgress = if (ok) 1f else 0f,
                                isCalibrated = ok
                            )
                        }
                    } else {
                        _state.update { it.copy(calibrationProgress = progress) }
                    }
                }

                val stftRes = stftEngine.computeStft(
                    pcmBuffer = frame,
                    offset = 0,
                    length = frame.size,
                    enabledMaterials = _state.value.enabledMaterials
                )

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUiEmitMs >= 45L) {
                    lastUiEmitMs = nowMs
                    if (waterfallBuffer.size >= 22) {
                        waterfallBuffer.removeFirst()
                    }
                    waterfallBuffer.addLast(stftRes.waterfallBandRow)
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f

                    _state.update { prev ->
                        prev.copy(
                            lastResult = stftRes,
                            waterfallHistory = waterfallBuffer.toList(),
                            frameSeq = prev.frameSeq + 1L,
                            dspProcessingMs = elapsedMs
                        )
                    }
                }
            }
        }

        captureJob?.cancel()
        captureJob = pipelineScope.launch(Dispatchers.IO) {
            val initResult = initBestAudioRecord()
            if (initResult == null) {
                frameChannel.close()
                _state.update {
                    it.copy(
                        isListening = false,
                        isCalibrating = false,
                        errorMessage = "خطا در دسترسی به پورت صوتی/میکروفون — مجوز RECORD_AUDIO و سخت‌افزار را بررسی کنید."
                    )
                }
                return@launch
            }

            val (record, actualRate, sourceName) = initResult
            audioRecord = record
            stftEngine.updateSampleRate(actualRate)

            try {
                record.startRecording()
                _state.update {
                    it.copy(
                        isListening = true,
                        sampleRateHz = actualRate,
                        nyquistHz = actualRate / 2,
                        audioSourceLabel = sourceName,
                        isCalibrated = false,
                        errorMessage = null
                    )
                }
                Log.i(TAG, "Passive audio pipeline started: ${actualRate}Hz ($sourceName)")

                val frameSize = stftEngine.fftSize
                val readBuffer = ShortArray(frameSize)
                while (isActive && _state.value.isListening) {
                    val readCount = record.read(readBuffer, 0, frameSize, AudioRecord.READ_BLOCKING)
                    if (readCount > 0) {
                        frameChannel.trySend(readBuffer.copyOf(readCount))
                    } else {
                        delay(10L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in 192kHz passive audio capture", e)
                _state.update {
                    it.copy(
                        isListening = false,
                        isCalibrating = false,
                        errorMessage = "خطای جریان پسیو صوتی: ${e.localizedMessage ?: "نامشخص"}"
                    )
                }
            } finally {
                frameChannel.close()
                releaseAudioRecordInternal()
            }
        }
    }

    fun startCalibration() {
        if (!_state.value.isListening) {
            startListening()
        }
        stftEngine.resetCalibration()
        pendingCalibrationFrames = calibrationTargetFrames
        _state.update {
            it.copy(
                isCalibrating = true,
                calibrationProgress = 0f,
                isCalibrated = false
            )
        }
    }

    fun stopListening() {
        _state.update { it.copy(isListening = false, isCalibrating = false) }
        pendingCalibrationFrames = 0
        captureJob?.cancel()
        captureJob = null
        dspJob?.cancel()
        dspJob = null
        releaseAudioRecordInternal()
    }

    fun release() {
        stopListening()
        pipelineScope.cancel()
    }

    fun setGainMultiplier(gain: Float) {
        _state.update { it.copy(gainMultiplier = gain.coerceIn(0.1f, 10.0f)) }
    }

    fun toggleMaterial(material: PassiveMaterialId) {
        _state.update { prev ->
            val updated = prev.enabledMaterials.toMutableSet()
            if (material in updated) {
                if (updated.size > 1) updated.remove(material)
            } else {
                updated.add(material)
            }
            prev.copy(enabledMaterials = updated)
        }
    }

    fun selectAllMaterials() {
        _state.update { it.copy(enabledMaterials = PassiveMaterialId.entries.toSet()) }
    }

    fun selectNonFerrousOnly() {
        val nonFerrous = PassiveMaterialId.entries.filter { !it.isFerrousOrClutter }.toSet()
        _state.update { it.copy(enabledMaterials = nonFerrous) }
    }

    private fun releaseAudioRecordInternal() {
        runCatching {
            audioRecord?.stop()
        }
        runCatching {
            audioRecord?.release()
        }
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun initBestAudioRecord(): Triple<AudioRecord, Int, String>? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
        )

        for (rate in candidateSampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuf <= 0) continue

            val bufSize = max(minBuf * 4, stftEngine.fftSize * 4)
            for ((source, label) in sources) {
                val record = runCatching {
                    AudioRecord(source, rate, channelConfig, audioFormat, bufSize)
                }.getOrNull()

                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    return Triple(record, record.sampleRate, label)
                }
                runCatching { record?.release() }
            }
        }
        return null
    }
}
