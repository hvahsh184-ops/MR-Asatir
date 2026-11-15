package com.example.sensors

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object VoiceNavigationFormatter {

    fun computeRelativeTurnDeg(targetBearingDeg: Double, deviceYawDeg: Double): Double {
        return ((targetBearingDeg - deviceYawDeg + 540.0) % 360.0) - 180.0
    }

    fun formatNavigationCue(
        distanceMeters: Double,
        targetBearingDeg: Double,
        deviceYawDeg: Double,
        usePersian: Boolean = true
    ): String {
        val relTurn = computeRelativeTurnDeg(targetBearingDeg, deviceYawDeg)
        val absTurn = abs(relTurn).roundToInt()
        val distRounded = distanceMeters.roundToInt().coerceAtLeast(0)
        val cardinal = if (usePersian) cardinalFa(targetBearingDeg) else cardinalEn(targetBearingDeg)

        if (distanceMeters <= 3.0) {
            return if (usePersian) {
                "تثبیت نقطه صفر! فاصله ${String.format(Locale.US, "%.1f", distanceMeters)} متر، هدف زیر پای شماست."
            } else {
                "Target locked! ${String.format(Locale.US, "%.1f", distanceMeters)} meters, right at your position."
            }
        }

        val directionPhrase = if (usePersian) {
            when {
                abs(relTurn) <= 15.0 -> "مستقیم به جلو حرکت کنید"
                relTurn in 15.0..60.0 -> "کمی به راست، $absTurn درجه بچرخید"
                relTurn > 60.0 -> "به راست بپیچید، $absTurn درجه"
                relTurn in -60.0..-15.0 -> "کمی به چپ، $absTurn درجه بچرخید"
                else -> "به چپ بپیچید، $absTurn درجه"
            }
        } else {
            when {
                abs(relTurn) <= 15.0 -> "continue straight ahead"
                relTurn in 15.0..60.0 -> "bear right $absTurn degrees"
                relTurn > 60.0 -> "turn right $absTurn degrees"
                relTurn in -60.0..-15.0 -> "bear left $absTurn degrees"
                else -> "turn left $absTurn degrees"
            }
        }

        return if (usePersian) {
            "فاصله $distRounded متر، به سمت $cardinal، $directionPhrase."
        } else {
            "$distRounded meters $cardinal, $directionPhrase."
        }
    }

    private fun cardinalFa(bearingDeg: Double): String {
        val norm = ((bearingDeg % 360.0) + 360.0) % 360.0
        val idx = ((norm + 22.5) / 45.0).toInt() % 8
        return when (idx) {
            0 -> "شمال"
            1 -> "شمال‌شرق"
            2 -> "شرق"
            3 -> "جنوب‌شرق"
            4 -> "جنوب"
            5 -> "جنوب‌غرب"
            6 -> "غرب"
            else -> "شمال‌غرب"
        }
    }

    private fun cardinalEn(bearingDeg: Double): String {
        val norm = ((bearingDeg % 360.0) + 360.0) % 360.0
        val idx = ((norm + 22.5) / 45.0).toInt() % 8
        return when (idx) {
            0 -> "north"
            1 -> "northeast"
            2 -> "east"
            3 -> "southeast"
            4 -> "south"
            5 -> "southwest"
            6 -> "west"
            else -> "northwest"
        }
    }
}

class VoiceNavigationService(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var persianAvailable = false

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _companionSpeechFinishedTick = MutableStateFlow(0L)
    val companionSpeechFinishedTick: StateFlow<Long> = _companionSpeechFinishedTick.asStateFlow()

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val fa = Locale("fa", "IR")
                    val faResult = tts?.setLanguage(fa) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    persianAvailable = faResult != TextToSpeech.LANG_MISSING_DATA &&
                        faResult != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!persianAvailable) {
                        tts?.language = Locale.US
                    }
                    tts?.setSpeechRate(1.02f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("companion_duplex_") == true) {
                                _companionSpeechFinishedTick.value = System.currentTimeMillis()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId?.startsWith("companion_duplex_") == true) {
                                _companionSpeechFinishedTick.value = System.currentTimeMillis()
                            }
                        }
                    })
                    _isReady.value = true
                }
            }
        }
    }

    fun speakNavigationCue(
        distanceMeters: Double,
        targetBearingDeg: Double,
        deviceYawDeg: Double,
        forceImmediate: Boolean = false
    ): String {
        val displayCue = VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = distanceMeters,
            targetBearingDeg = targetBearingDeg,
            deviceYawDeg = deviceYawDeg,
            usePersian = true
        )
        val spokenCue = VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = distanceMeters,
            targetBearingDeg = targetBearingDeg,
            deviceYawDeg = deviceYawDeg,
            usePersian = persianAvailable
        )
        if (_isReady.value) {
            val engine = tts
            if (engine != null && (forceImmediate || !engine.isSpeaking)) {
                engine.speak(
                    spokenCue,
                    if (forceImmediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "nav_cue_${System.currentTimeMillis()}"
                )
            }
        }
        return displayCue
    }

    fun speakCustomMessage(
        messageFa: String,
        forceImmediate: Boolean = true,
        notifyDuplexDone: Boolean = false
    ) {
        if (!_isReady.value || messageFa.isBlank()) {
            if (notifyDuplexDone) _companionSpeechFinishedTick.value = System.currentTimeMillis()
            return
        }
        val engine = tts
        if (engine == null) {
            if (notifyDuplexDone) _companionSpeechFinishedTick.value = System.currentTimeMillis()
            return
        }
        val prefix = if (notifyDuplexDone) "companion_duplex_" else "companion_"
        if (forceImmediate || !engine.isSpeaking) {
            engine.speak(
                messageFa.take(500),
                if (forceImmediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "$prefix${System.currentTimeMillis()}"
            )
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        _isReady.value = false
    }
}
