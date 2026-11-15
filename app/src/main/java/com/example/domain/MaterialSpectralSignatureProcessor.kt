package com.example.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SpectralMaterialModel(
    val id: String,
    val displayName: String,
    val category: String,
    val spectralVector6: DoubleArray,
    val isFerrousOrGeologicalNoise: Boolean
)

data class MaterialSignatureMatch(
    val id: String,
    val displayName: String,
    val category: String,
    val similarityPercent: Int,
    val spectralAngleDeg: Double,
    val isFerrousOrGeologicalNoise: Boolean
)

data class MaterialSignatureAnalysis(
    val primaryClassLabel: String,
    val dominantCategory: String,
    val topSimilarityPercent: Int,
    val isFerrousOrNoiseDominant: Boolean,
    val rankedMatches: List<MaterialSignatureMatch>,
    val spectralBandProfile6: List<Float>,
    val signalPolarityLabel: String,
    val physicalCaveat: String
)

/**
 * Dedicated real-time Material Spectral Signature Processor.
 *
 * Receives incoming magnetometer values (either directly as a live time-series stream or via
 * precomputed STFT + AdaptiveDepthFilter output) and compares the 6-band spectral signature and
 * signal polarity against reference geophysical models for Gold/Diamagnetic Proxy, Silver/High-Conductivity,
 * Copper/Oxide, Ferrous Iron, and Geological Mineral Noise.
 */
class MaterialSpectralSignatureProcessor(
    private val referenceModels: List<SpectralMaterialModel> = DEFAULT_REFERENCE_MODELS
) {

    fun getReferenceModels(): List<SpectralMaterialModel> = referenceModels

    /**
     * Direct real-time stream entry point: accepts incoming magnetometer readings, computes STFT
     * spectral energy distribution and adaptive depth filtering, and compares against reference models.
     */
    fun processRawMagnetometerStream(
        samplesUt: List<Double>,
        baselineMeanUt: Double,
        baselineStdUt: Double,
        sampleRateHz: Double = 25.0,
        sweepSpeedMps: Double = 0.40,
        depthBandMode: Int = 0,
        userBaseThresholdUt: Float = 8f,
        opticalMeta: ThermalStylePixelResult? = null,
        depthFilter: AdaptiveDepthFilter = AdaptiveDepthFilter()
    ): MaterialSignatureAnalysis {
        val currentDelta = if (samplesUt.isNotEmpty()) samplesUt.last() - baselineMeanUt else 0.0
        val stft = if (samplesUt.size >= 16) {
            FieldReceiverMath.computeMagnetometerStft(
                samplesUt = samplesUt,
                sampleRateHz = sampleRateHz,
                sweepSpeedMps = sweepSpeedMps,
                currentDeltaUt = currentDelta,
                baselineStdUt = baselineStdUt
            )
        } else null

        val filterResult = depthFilter.filterAndIsolateDeepSignal(
            samplesUt = samplesUt,
            baselineMeanUt = baselineMeanUt,
            baselineStdUt = baselineStdUt,
            stft = stft,
            opticalMeta = opticalMeta,
            userBaseThresholdUt = userBaseThresholdUt,
            depthBandMode = depthBandMode,
            sweepSpeedMps = sweepSpeedMps
        )

        return analyzeLiveSignature(
            stft = stft,
            depthFilter = filterResult,
            baselineStdUt = baselineStdUt,
            opticalMeta = opticalMeta
        )
    }

    /**
     * Real-time spectral matching using an already-computed STFT spectrum and adaptive depth envelope.
     */
    fun analyzeLiveSignature(
        stft: MagStftResult?,
        depthFilter: AdaptiveDepthFilterResult,
        baselineStdUt: Double,
        opticalMeta: ThermalStylePixelResult? = null
    ): MaterialSignatureAnalysis {
        if (stft == null || stft.frequenciesHz.isEmpty() || stft.magnitudesUt.isEmpty()) {
            return MaterialSignatureAnalysis(
                primaryClassLabel = "در انتظار تکمیل طیف STFT (حداقل ۱۶ نمونه مگنتومتر)",
                dominantCategory = "PENDING",
                topSimilarityPercent = 0,
                isFerrousOrNoiseDominant = false,
                rankedMatches = emptyList(),
                spectralBandProfile6 = List(6) { 0f },
                signalPolarityLabel = "نامشخص",
                physicalCaveat = "تا تکمیل پنجره فرکانسی حرکتی، مقایسه امضای طیفی غیرفعال است."
            )
        }

        // Bin real STFT bins into 6 canonical frequency energy bands (0.15..10.6 Hz)
        val bandBounds = doubleArrayOf(0.15, 0.80, 1.60, 2.80, 4.50, 7.00, 10.60)
        val rawBands = DoubleArray(6)
        for (i in stft.frequenciesHz.indices) {
            val f = stft.frequenciesHz[i]
            val m = stft.magnitudesUt[i]
            for (b in 0 until 6) {
                if (f >= bandBounds[b] && f < bandBounds[b + 1]) {
                    rawBands[b] += m * m
                    break
                }
            }
        }
        val ampBands = DoubleArray(6) { sqrt(rawBands[it]) }
        var norm = 0.0
        for (v in ampBands) norm += v * v
        norm = sqrt(norm)
        val normBands = if (norm > 1e-6) DoubleArray(6) { ampBands[it] / norm } else DoubleArray(6) { 0.0 }

        val env = depthFilter.deepEnvelopeUt
        val absEnv = abs(env)
        val hasSignificantMagSignal = absEnv >= max(baselineStdUt * 2.0, 1.5) ||
            stft.peakAmplitudeUt >= max(baselineStdUt * 1.6, 1.1)
        val isNegativeTrough = env < -max(baselineStdUt * 1.4, 1.2)
        val isStrongFerrousSpike = absEnv >= 14.0 || (env > 8.0 && depthFilter.shallowClutterUt > 2.4)
        val opticalOxideHint = (opticalMeta?.peakChromaIndex ?: 0.5f) >= 0.64f
        val opticalLimeVoidHint = (opticalMeta?.saroojMatchPct ?: 0f) >= 3.0f

        val polarityLabel = when {
            !hasSignificantMagSignal -> "خنثی / خط پایه آرام"
            isNegativeTrough -> String.format(Locale.US, "افت منفی دیامغناطیس/حفره (%+.1f µT)", env)
            env > 0.0 -> String.format(Locale.US, "جهش مثبت پارا/فرومغناطیس (%+.1f µT)", env)
            else -> String.format(Locale.US, "انحراف خفیف (%+.1f µT)", env)
        }

        if (!hasSignificantMagSignal && norm <= 1e-6) {
            return MaterialSignatureAnalysis(
                primaryClassLabel = "محیط آرام — ناهنجاری مغناطیسی فعالی دیده نشد",
                dominantCategory = "BASELINE",
                topSimilarityPercent = 0,
                isFerrousOrNoiseDominant = false,
                rankedMatches = emptyList(),
                spectralBandProfile6 = List(6) { 0f },
                signalPolarityLabel = polarityLabel,
                physicalCaveat = "گوشی را آرام و تراز روی سطح خاک حرکت دهید تا طیف حرکتی مواد ثبت شود."
            )
        }

        val matches = referenceModels.map { model ->
            val modifier = when (model.id) {
                "gold" -> when {
                    isStrongFerrousSpike -> -0.34
                    depthFilter.surfaceClutterSuppressed -> -0.10
                    isNegativeTrough && stft.lowBandEnergyRatio >= 0.58 -> +0.16
                    !isStrongFerrousSpike && stft.lowBandEnergyRatio >= 0.62 && opticalLimeVoidHint -> +0.10
                    else -> 0.0
                }
                "silver" -> when {
                    isStrongFerrousSpike -> -0.30
                    isNegativeTrough && stft.lowBandEnergyRatio in 0.45..0.75 -> +0.11
                    else -> 0.0
                }
                "copper" -> when {
                    isStrongFerrousSpike -> -0.20
                    opticalOxideHint && !isStrongFerrousSpike -> +0.12
                    else -> 0.0
                }
                "iron" -> when {
                    isStrongFerrousSpike -> +0.26
                    env >= 6.0 && depthFilter.shallowClutterUt >= 1.5 -> +0.16
                    isNegativeTrough -> -0.22
                    else -> -0.05
                }
                else -> when {
                    baselineStdUt >= 1.25 || depthFilter.surfaceClutterSuppressed -> +0.18
                    stft.lowBandEnergyRatio < 0.35 -> +0.10
                    else -> -0.04
                }
            }

            val samDeg = computeSpectralAngleDeg(normBands, model.spectralVector6)
            val cosineSim = cos(Math.toRadians(samDeg)).coerceIn(0.0, 1.0)
            val adjustedScore = (cosineSim + modifier).coerceIn(0.0, 1.0)
            MaterialSignatureMatch(
                id = model.id,
                displayName = model.displayName,
                category = model.category,
                similarityPercent = (adjustedScore * 100.0).toInt().coerceIn(0, 100),
                spectralAngleDeg = samDeg,
                isFerrousOrGeologicalNoise = model.isFerrousOrGeologicalNoise
            )
        }.sortedByDescending { it.similarityPercent }

        val best = matches.first()
        val label = if (!hasSignificantMagSignal && best.similarityPercent < 60) {
            "تغییر جزئی خط پایه (امضای ماده ضعیف است)"
        } else {
            best.displayName
        }

        val caveat = if (best.isFerrousOrGeologicalNoise) {
            "فیلتر تطبیقی این الگو را به‌عنوان آهن یا نویز زمین‌شناسی علامت‌گذاری کرد تا اهداف غیرآهنی با آن اشتباه نشوند."
        } else {
            "مقایسه طیفی بر اساس شکل موج مگنتومتر + افت دیامغناطیس/کانی سطحی است؛ تشخیص مستقیم طلا/نقره خالص در عمق نیازمند کویل فعال پالسی/VLF است."
        }

        return MaterialSignatureAnalysis(
            primaryClassLabel = label,
            dominantCategory = best.category,
            topSimilarityPercent = best.similarityPercent,
            isFerrousOrNoiseDominant = best.isFerrousOrGeologicalNoise,
            rankedMatches = matches,
            spectralBandProfile6 = normBands.map { it.toFloat() },
            signalPolarityLabel = polarityLabel,
            physicalCaveat = caveat
        )
    }

    companion object {
        val DEFAULT_REFERENCE_MODELS: List<SpectralMaterialModel> = listOf(
            SpectralMaterialModel(
                id = "gold",
                displayName = "طلا / هدف دیامغناطیس غیرآهنی",
                category = "NON_FERROUS_DIAMAG",
                spectralVector6 = doubleArrayOf(0.80, 0.50, 0.25, 0.14, 0.08, 0.05),
                isFerrousOrGeologicalNoise = false
            ),
            SpectralMaterialModel(
                id = "silver",
                displayName = "نقره / رسانای غیرآهنی باخلوص بالا",
                category = "NON_FERROUS_DIAMAG",
                spectralVector6 = doubleArrayOf(0.72, 0.58, 0.31, 0.18, 0.09, 0.06),
                isFerrousOrGeologicalNoise = false
            ),
            SpectralMaterialModel(
                id = "copper",
                displayName = "مس / برنز و هاله اکسید معدنی",
                category = "COPPER_OXIDE_PROXY",
                spectralVector6 = doubleArrayOf(0.56, 0.64, 0.42, 0.24, 0.14, 0.09),
                isFerrousOrGeologicalNoise = false
            ),
            SpectralMaterialModel(
                id = "iron",
                displayName = "آهن رایج / فولاد فرومغناطیس",
                category = "FERROUS_IRON",
                spectralVector6 = doubleArrayOf(0.26, 0.34, 0.48, 0.50, 0.44, 0.38),
                isFerrousOrGeologicalNoise = true
            ),
            SpectralMaterialModel(
                id = "geological_noise",
                displayName = "نویز زمین‌شناسی / کانی آهندار پراکنده",
                category = "GEOLOGICAL_NOISE",
                spectralVector6 = doubleArrayOf(0.36, 0.38, 0.42, 0.44, 0.43, 0.41),
                isFerrousOrGeologicalNoise = true
            )
        )

        fun computeSpectralAngleDeg(a: DoubleArray, b: DoubleArray): Double {
            var dot = 0.0
            var nA = 0.0
            var nB = 0.0
            for (i in 0 until min(a.size, b.size)) {
                dot += a[i] * b[i]
                nA += a[i] * a[i]
                nB += b[i] * b[i]
            }
            val denom = sqrt(nA) * sqrt(nB)
            if (denom < 1e-9) return 90.0
            return Math.toDegrees(acos((dot / denom).coerceIn(-1.0, 1.0)))
        }
    }
}
