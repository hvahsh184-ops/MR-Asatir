package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.domain.FieldReceiverMath
import com.example.domain.MagnetometerReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * 3-Axis Adaptive Kalman Filter for Magnetometer Stabilization.
 *
 * Dynamically adjusts process noise Q based on angular rate (from gyroscope)
 * and linear acceleration (from accelerometer) to provide instantaneous responsiveness
 * during rapid sweeps while aggressively eliminating jitter during slow sweeps and steady holds.
 */
class MagneticKalmanFilter(
    private val baseMeasurementNoiseR: Double = 0.40,
    private val minProcessNoiseQ: Double = 0.035,
    private val maxProcessNoiseQ: Double = 3.20
) {
    private var stateBx = 0.0
    private var stateBy = 0.0
    private var stateBz = 0.0

    private var covPx = 1.0
    private var covPy = 1.0
    private var covPz = 1.0

    private var isInitialized = false
    var lastKalmanGainAvg: Double = 0.5
        private set

    fun reset() {
        isInitialized = false
        covPx = 1.0
        covPy = 1.0
        covPz = 1.0
        lastKalmanGainAvg = 0.5
    }

    /**
     * Filters raw 3-axis magnetometer readings using gyro and accel cues.
     */
    fun update(
        rawBx: Double,
        rawBy: Double,
        rawBz: Double,
        gyroRateRadS: Double = 0.0,
        linearAccelMagMps2: Double = 0.0,
        timestampMs: Long = System.currentTimeMillis()
    ): MagnetometerReading {
        if (!isInitialized) {
            stateBx = rawBx
            stateBy = rawBy
            stateBz = rawBz
            isInitialized = true
            val initMag = FieldReceiverMath.computeMagnitude(rawBx, rawBy, rawBz)
            return MagnetometerReading(rawBx, rawBy, rawBz, initMag, timestampMs)
        }

        // Dynamically scale process noise Q with motion dynamics
        // High gyro/accel -> high Q -> fast tracking without lag
        // Low gyro/accel -> low Q -> strong jitter and thermal drift rejection
        val motionFactor = (gyroRateRadS * 1.8 + linearAccelMagMps2 * 0.45).coerceIn(0.0, 1.0)
        val dynQ = minProcessNoiseQ + (maxProcessNoiseQ - minProcessNoiseQ) * (motionFactor * motionFactor)

        // Predict step
        covPx += dynQ
        covPy += dynQ
        covPz += dynQ

        // Update step
        val kx = covPx / (covPx + baseMeasurementNoiseR)
        val ky = covPy / (covPy + baseMeasurementNoiseR)
        val kz = covPz / (covPz + baseMeasurementNoiseR)

        stateBx += kx * (rawBx - stateBx)
        stateBy += ky * (rawBy - stateBy)
        stateBz += kz * (rawBz - stateBz)

        covPx *= (1.0 - kx)
        covPy *= (1.0 - ky)
        covPz *= (1.0 - kz)

        lastKalmanGainAvg = (kx + ky + kz) / 3.0
        val filteredMag = FieldReceiverMath.computeMagnitude(stateBx, stateBy, stateBz)

        return MagnetometerReading(
            bxUt = stateBx,
            byUt = stateBy,
            bzUt = stateBz,
            magnitudeUt = filteredMag,
            timestampMs = timestampMs
        )
    }
}

/**
 * Integrated Sensor Fusion Manager.
 *
 * Collects synchronized raw inputs from:
 * 1) Magnetometer (Sensor.TYPE_MAGNETIC_FIELD)
 * 2) Gyroscope (Sensor.TYPE_GYROSCOPE)
 * 3) Accelerometer / Linear Acceleration (Sensor.TYPE_LINEAR_ACCELERATION or TYPE_ACCELEROMETER)
 *
 * Runs real-time 3D Kalman filtering to produce a stabilized magnetometer stream
 * before feeding into the AdaptiveDepthFilter and MaterialSpectralSignatureProcessor.
 */
class SensorFusionManager(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscopeSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val kalmanFilter = MagneticKalmanFilter()

    private val _rawMagnetometer = MutableStateFlow<MagnetometerReading?>(null)
    val rawMagnetometer: StateFlow<MagnetometerReading?> = _rawMagnetometer.asStateFlow()

    private val _stabilizedMagnetometer = MutableStateFlow<MagnetometerReading?>(null)
    val stabilizedMagnetometer: StateFlow<MagnetometerReading?> = _stabilizedMagnetometer.asStateFlow()

    private val _gyroRateRadS = MutableStateFlow(0.0)
    val gyroRateRadS: StateFlow<Double> = _gyroRateRadS.asStateFlow()

    private val _linearAccelMps2 = MutableStateFlow(0.0)
    val linearAccelMps2: StateFlow<Double> = _linearAccelMps2.asStateFlow()

    private val _kalmanGain = MutableStateFlow(0.5)
    val kalmanGain: StateFlow<Double> = _kalmanGain.asStateFlow()

    private val _isStabilizationActive = MutableStateFlow(false)
    val isStabilizationActive: StateFlow<Boolean> = _isStabilizationActive.asStateFlow()

    private var isRunning = false

    private val fusionEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    if (event.values.size >= 3) {
                        val gx = event.values[0].toDouble()
                        val gy = event.values[1].toDouble()
                        val gz = event.values[2].toDouble()
                        val rate = sqrt(gx * gx + gy * gy + gz * gz)
                        _gyroRateRadS.value = rate
                    }
                }
                Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                    if (event.values.size >= 3) {
                        val ax = event.values[0].toDouble()
                        val ay = event.values[1].toDouble()
                        val az = event.values[2].toDouble()
                        val rawMag = sqrt(ax * ax + ay * ay + az * az)
                        // If standard accelerometer (includes 9.81 m/s^2 gravity), subtract nominal gravity
                        val netAccel = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                            kotlin.math.abs(rawMag - 9.81)
                        } else rawMag
                        _linearAccelMps2.value = (_linearAccelMps2.value * 0.70) + (netAccel * 0.30)
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (event.values.size >= 3) {
                        val bx = event.values[0].toDouble()
                        val by = event.values[1].toDouble()
                        val bz = event.values[2].toDouble()
                        val rawMag = FieldReceiverMath.computeMagnitude(bx, by, bz)
                        val now = System.currentTimeMillis()

                        val raw = MagnetometerReading(
                            bxUt = bx,
                            byUt = by,
                            bzUt = bz,
                            magnitudeUt = rawMag,
                            timestampMs = now
                        )
                        _rawMagnetometer.value = raw

                        val filtered = kalmanFilter.update(
                            rawBx = bx,
                            rawBy = by,
                            rawBz = bz,
                            gyroRateRadS = _gyroRateRadS.value,
                            linearAccelMagMps2 = _linearAccelMps2.value,
                            timestampMs = now
                        )
                        _stabilizedMagnetometer.value = filtered
                        _kalmanGain.value = kalmanFilter.lastKalmanGainAvg
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        kalmanFilter.reset()
        _isStabilizationActive.value = true

        magneticSensor?.let {
            sensorManager.registerListener(fusionEventListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscopeSensor?.let {
            sensorManager.registerListener(fusionEventListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(fusionEventListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        _isStabilizationActive.value = false
        sensorManager.unregisterListener(fusionEventListener)
    }

    fun resetFilter() {
        kalmanFilter.reset()
    }

    /**
     * Standalone pure filtering function for algorithm verification and testing.
     */
    fun filterSample(
        raw: MagnetometerReading,
        gyroRateRadS: Double = 0.0,
        linearAccelMagMps2: Double = 0.0
    ): MagnetometerReading {
        return kalmanFilter.update(
            rawBx = raw.bxUt,
            rawBy = raw.byUt,
            rawBz = raw.bzUt,
            gyroRateRadS = gyroRateRadS,
            linearAccelMagMps2 = linearAccelMagMps2,
            timestampMs = raw.timestampMs
        )
    }
}
