package com.example.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class WatchdogAlertType {
    NONE,
    TARGET_OVERSHOOT,
    DEEP_ANOMALY_OVERSHOOT,
    SURFACE_CLUTTER_FALSE_LEAD
}

data class WatchdogDecision(
    val alertType: WatchdogAlertType,
    val bannerTitleFa: String,
    val bannerDetailFa: String,
    val spokenMessageFa: String,
    val shouldConsultCloudVision: Boolean,
    val cloudVerificationPromptFa: String
)

/**
 * Proactive Field Companion Watchdog (active ONLY when explicitly permitted by the user).
 *
 * Monitors live GPS distance to target, AdaptiveDepthFilter deep envelope peaks, and
 * MaterialSpectralSignatureProcessor classifications to catch:
 * 1) Walking past / overshooting a locked target or strong deep anomaly peak.
 * 2) Mistaking high-frequency shallow iron clutter for a deep buried anomaly.
 */
class FieldCompanionWatchdog(
    private val alertCooldownMs: Long = 14_000L
) {
    private var closestTargetDistanceM: Double? = null
    private var peakDeepEnvelopeUt: Double = 0.0
    private var peakWasNonFerrousOrGoldProxy: Boolean = false
    private var lastAlertAtMs: Long = -100_000L

    fun reset() {
        closestTargetDistanceM = null
        peakDeepEnvelopeUt = 0.0
        peakWasNonFerrousOrGoldProxy = false
        lastAlertAtMs = -100_000L
    }

    fun evaluateStep(
        nowMs: Long,
        enabled: Boolean,
        distanceToTargetM: Double?,
        isMagCalibrated: Boolean,
        depthFilter: AdaptiveDepthFilterResult?,
        materialSig: MaterialSignatureAnalysis?
    ): WatchdogDecision? {
        if (!enabled) return null

        // Track closest proximity to GPS locked target
        if (distanceToTargetM != null) {
            if (distanceToTargetM > 25.0) {
                closestTargetDistanceM = null
            } else {
                val prevMin = closestTargetDistanceM
                if (prevMin == null || distanceToTargetM < prevMin) {
                    closestTargetDistanceM = distanceToTargetM
                }
            }
        }

        // Track peak sustained deep anomaly envelope (ignoring suppressed shallow clutter)
        val envAbs = if (depthFilter != null && !depthFilter.surfaceClutterSuppressed) {
            abs(depthFilter.deepEnvelopeUt)
        } else 0.0

        val triggerLevel = if (depthFilter != null) {
            max(depthFilter.adaptiveThresholdUt * 1.10, 6.0)
        } else 6.0

        if (isMagCalibrated && envAbs >= triggerLevel && envAbs > peakDeepEnvelopeUt) {
            peakDeepEnvelopeUt = envAbs
            peakWasNonFerrousOrGoldProxy = materialSig?.isFerrousOrNoiseDominant == false
        }

        if (nowMs - lastAlertAtMs < alertCooldownMs) {
            return null
        }

        // 1. Target Overshoot Check
        val minDist = closestTargetDistanceM
        if (minDist != null && minDist <= 4.5 && distanceToTargetM != null && distanceToTargetM >= minDist + 2.8) {
            val overshootM = (distanceToTargetM - minDist).roundToInt().coerceAtLeast(2)
            lastAlertAtMs = nowMs
            closestTargetDistanceM = distanceToTargetM
            return WatchdogDecision(
                alertType = WatchdogAlertType.TARGET_OVERSHOOT,
                bannerTitleFa = "هشدار ناظر: از نقطه هدف رد شدی!",
                bannerDetailFa = "تا ${String.format(java.util.Locale.US, "%.1f", minDist)} متری هدف رفته بودی اما الان ${String.format(java.util.Locale.US, "%.1f", distanceToTargetM)} متر دور شدی. حدود $overshootM متر برگرد عقب.",
                spokenMessageFa = "صبر کن رفیق! از روی نقطه هدف رد شدی؛ حدود $overshootM متر برگرد عقب.",
                shouldConsultCloudVision = true,
                cloudVerificationPromptFa = "کاربر از کنار نقطه هدف رد شده و فاصله‌اش دوباره زیاد شده است. با توجه به تصویر زمین و تله‌متری فعلی خیلی کوتاه و صریح بگو آیا باید برگردد روی نقطه یا نشانه سطحی خاصی دارد؟"
            )
        }

        // 2. Deep Anomaly Peak Overshoot Check
        if (isMagCalibrated && depthFilter != null && peakDeepEnvelopeUt >= 7.0) {
            val droppedPastPeak = envAbs <= peakDeepEnvelopeUt * 0.42 && !depthFilter.surfaceClutterSuppressed
            if (droppedPastPeak) {
                val rememberedPeak = peakDeepEnvelopeUt
                val nonFerrousHint = peakWasNonFerrousOrGoldProxy
                peakDeepEnvelopeUt = 0.0
                peakWasNonFerrousOrGoldProxy = false
                lastAlertAtMs = nowMs
                val kindNote = if (nonFerrousHint) "امضای غیرآهنی/دیامغناطیس" else "ناهنجاری عمقی"
                return WatchdogDecision(
                    alertType = WatchdogAlertType.DEEP_ANOMALY_OVERSHOOT,
                    bannerTitleFa = "هشدار ناظر: از اوج $kindNote عبور کردی!",
                    bannerDetailFa = "اوج پوش عمقی ${String.format(java.util.Locale.US, "%.1f", rememberedPeak)} µT ثبت شد و الان افت کرد. ۲ تا ۳ متر آرام برگرد عقب و دوباره اسکن کن.",
                    spokenMessageFa = "وایسا! از روی یک اوج $kindNote رد شدی؛ دو تا سه متر برگرد عقب و دوباره روی همین لکه بگیر.",
                    shouldConsultCloudVision = true,
                    cloudVerificationPromptFa = "الان از روی اوج ناهنجاری ${String.format(java.util.Locale.US, "%.1f", rememberedPeak)} میکروتسلا رد شدیم و سیگنال افت کرد. با تصویر و طیف فعلی خیلی کوتاه بگو آیا اینجا ارزش برگشت و بازبینی دارد یا خطای طبیعی است؟"
                )
            }
        }

        // 3. Shallow Ferrous Clutter False-Lead Guard
        if (isMagCalibrated && depthFilter != null &&
            depthFilter.surfaceClutterSuppressed &&
            abs(depthFilter.rawDeltaUt) >= 16.0 &&
            materialSig?.isFerrousOrNoiseDominant == true
        ) {
            lastAlertAtMs = nowMs
            return WatchdogDecision(
                alertType = WatchdogAlertType.SURFACE_CLUTTER_FALSE_LEAD,
                bannerTitleFa = "هشدار تصحیح خطا: تداخل سطحی / خرده‌آهن",
                bannerDetailFa = "اسپایک تیز سطحی (${String.format(java.util.Locale.US, "%+.1f", depthFilter.rawDeltaUt)} µT) با امضای آهن/نویز مسدود شد تا با هدف عمقی اشتباه نشود.",
                spokenMessageFa = "حواست باشد، این سیگنال تیز شبیه خرده‌آهن یا نویز سطحی است؛ رویش حساب نکن.",
                shouldConsultCloudVision = false,
                cloudVerificationPromptFa = ""
            )
        }

        return null
    }
}
