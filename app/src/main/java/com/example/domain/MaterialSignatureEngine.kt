package com.example.domain

data class SatelliteMaterialSignatureSummary(
    val carbonateSaroojSamDeg: Double?,
    val ironOxideBrickSamDeg: Double?,
    val hydrothermalGoldCopperHostSamDeg: Double?,
    val dryVoidMasonrySamDeg: Double?,
    val dominantMaterialLabel: String
)

/**
 * Compatibility facade delegating to the dedicated [AdaptiveDepthFilter] and
 * [MaterialSpectralSignatureProcessor] classes.
 */
object MaterialSignatureEngine {

    val adaptiveDepthFilter: AdaptiveDepthFilter = AdaptiveDepthFilter()
    val spectralProcessor: MaterialSpectralSignatureProcessor = MaterialSpectralSignatureProcessor()

    fun computeAdaptiveDepthFilter(
        samplesUt: List<Double>,
        baselineMeanUt: Double,
        baselineStdUt: Double,
        stft: MagStftResult?,
        opticalMeta: ThermalStylePixelResult?,
        userBaseThresholdUt: Float,
        depthBandMode: Int = 0,
        sweepSpeedMps: Double = 0.40
    ): AdaptiveDepthFilterResult = adaptiveDepthFilter.filterAndIsolateDeepSignal(
        samplesUt = samplesUt,
        baselineMeanUt = baselineMeanUt,
        baselineStdUt = baselineStdUt,
        stft = stft,
        opticalMeta = opticalMeta,
        userBaseThresholdUt = userBaseThresholdUt,
        depthBandMode = depthBandMode,
        sweepSpeedMps = sweepSpeedMps
    )

    fun analyzeLiveMaterialSignature(
        stft: MagStftResult?,
        depthFilter: AdaptiveDepthFilterResult,
        baselineStdUt: Double,
        opticalMeta: ThermalStylePixelResult? = null
    ): MaterialSignatureAnalysis = spectralProcessor.analyzeLiveSignature(
        stft = stft,
        depthFilter = depthFilter,
        baselineStdUt = baselineStdUt,
        opticalMeta = opticalMeta
    )

    fun computeVectorAngleDeg(a: DoubleArray, b: DoubleArray): Double =
        MaterialSpectralSignatureProcessor.computeSpectralAngleDeg(a, b)
}
