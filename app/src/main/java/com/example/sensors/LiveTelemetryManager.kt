package com.example.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
import com.example.domain.FieldReceiverMath
import com.example.domain.MagnetometerReading
import com.example.domain.QuaternionData
import com.example.domain.RadarMathEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveGpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val timestampMs: Long
)

class LiveTelemetryManager(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val linearAccelSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val lightSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val pressureSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _gpsFix = MutableStateFlow<LiveGpsFix?>(null)
    val gpsFix: StateFlow<LiveGpsFix?> = _gpsFix.asStateFlow()

    private val _quaternion = MutableStateFlow(
        QuaternionData(
            w = 1.0,
            x = 0.0,
            y = 0.0,
            z = 0.0,
            yawDeg = 0.0,
            pitchDeg = 0.0,
            rollDeg = 0.0
        )
    )
    val quaternion: StateFlow<QuaternionData> = _quaternion.asStateFlow()

    private val _hasRotationSensor = MutableStateFlow(rotationSensor != null)
    val hasRotationSensor: StateFlow<Boolean> = _hasRotationSensor.asStateFlow()

    private val _hasMagnetometer = MutableStateFlow(magneticSensor != null)
    val hasMagnetometer: StateFlow<Boolean> = _hasMagnetometer.asStateFlow()

    private val _magnetometerReading = MutableStateFlow<MagnetometerReading?>(null)
    val magnetometerReading: StateFlow<MagnetometerReading?> = _magnetometerReading.asStateFlow()

    private val _linearAccelMagMps2 = MutableStateFlow(0.0)
    val linearAccelMagMps2: StateFlow<Double> = _linearAccelMagMps2.asStateFlow()

    private val _ambientLightLux = MutableStateFlow<Float?>(null)
    val ambientLightLux: StateFlow<Float?> = _ambientLightLux.asStateFlow()

    private val _baroPressureHpa = MutableStateFlow<Float?>(null)
    val baroPressureHpa: StateFlow<Float?> = _baroPressureHpa.asStateFlow()

    private var locationStarted = false
    private var sensorsStarted = false
    private var magnetometerStarted = false
    private var fieldAssistStarted = false
    private var toneGenerator: ToneGenerator? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            _gpsFix.value = LiveGpsFix(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude,
                accuracyM = loc.accuracy,
                speedMps = loc.speed,
                timestampMs = loc.time
            )
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                _quaternion.value = RadarMathEngine.rotationVectorToQuaternion(event.values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val sensorFusionManager = SensorFusionManager(appContext)

    private val magnetometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD && event.values.size >= 3) {
                val bx = event.values[0].toDouble()
                val by = event.values[1].toDouble()
                val bz = event.values[2].toDouble()
                val rawMag = FieldReceiverMath.computeMagnitude(bx, by, bz)
                val rawReading = MagnetometerReading(
                    bxUt = bx,
                    byUt = by,
                    bzUt = bz,
                    magnitudeUt = rawMag,
                    timestampMs = System.currentTimeMillis()
                )
                val stabilized = sensorFusionManager.filterSample(
                    raw = rawReading,
                    gyroRateRadS = sensorFusionManager.gyroRateRadS.value,
                    linearAccelMagMps2 = _linearAccelMagMps2.value
                )
                _magnetometerReading.value = stabilized
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val fieldAssistListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> if (event.values.size >= 3) {
                    val ax = event.values[0].toDouble()
                    val ay = event.values[1].toDouble()
                    val az = event.values[2].toDouble()
                    val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    _linearAccelMagMps2.value = (_linearAccelMagMps2.value * 0.65) + (mag * 0.35)
                }
                Sensor.TYPE_LIGHT -> if (event.values.isNotEmpty()) {
                    _ambientLightLux.value = event.values[0]
                }
                Sensor.TYPE_PRESSURE -> if (event.values.isNotEmpty()) {
                    _baroPressureHpa.value = event.values[0]
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (locationStarted) return
        locationStarted = true

        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _gpsFix.value == null) {
                    _gpsFix.value = LiveGpsFix(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitudeM = loc.altitude,
                        accuracyM = loc.accuracy,
                        speedMps = loc.speed,
                        timestampMs = loc.time
                    )
                }
            }

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(800L)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }.onFailure {
            locationStarted = false
        }
    }

    fun stopLocationUpdates() {
        if (!locationStarted) return
        locationStarted = false
        runCatching { fusedClient.removeLocationUpdates(locationCallback) }
    }

    fun startOrientationSensor() {
        if (sensorsStarted || rotationSensor == null) return
        sensorsStarted = true
        runCatching {
            sensorManager?.registerListener(
                sensorListener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stopOrientationSensor() {
        if (!sensorsStarted) return
        sensorsStarted = false
        runCatching { sensorManager?.unregisterListener(sensorListener) }
    }

    fun startMagnetometer() {
        runCatching { sensorFusionManager.start() }
        if (!magnetometerStarted && magneticSensor != null) {
            magnetometerStarted = true
            runCatching {
                sensorManager?.registerListener(
                    magnetometerListener,
                    magneticSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }
        if (!fieldAssistStarted) {
            fieldAssistStarted = true
            runCatching {
                linearAccelSensor?.let {
                    sensorManager?.registerListener(fieldAssistListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                lightSensor?.let {
                    sensorManager?.registerListener(fieldAssistListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                pressureSensor?.let {
                    sensorManager?.registerListener(fieldAssistListener, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
    }

    fun stopMagnetometer() {
        runCatching { sensorFusionManager.stop() }
        if (magnetometerStarted) {
            magnetometerStarted = false
            runCatching { sensorManager?.unregisterListener(magnetometerListener) }
        }
        if (fieldAssistStarted) {
            fieldAssistStarted = false
            runCatching { sensorManager?.unregisterListener(fieldAssistListener) }
        }
    }

    fun playProximityBeep(isTargetLocked: Boolean) {
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            }
            val toneType = if (isTargetLocked) {
                ToneGenerator.TONE_PROP_ACK
            } else {
                ToneGenerator.TONE_PROP_BEEP
            }
            val durationMs = if (isTargetLocked) 180 else 70
            toneGenerator?.startTone(toneType, durationMs)
        }
    }

    fun playMagneticGeigerTone(isStrongAnomaly: Boolean) {
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            }
            val toneType = if (isStrongAnomaly) {
                ToneGenerator.TONE_PROP_BEEP2
            } else {
                ToneGenerator.TONE_PROP_BEEP
            }
            toneGenerator?.startTone(toneType, if (isStrongAnomaly) 110 else 55)
        }
    }

    fun playConfirmedDetectionAlert(isDualSensor: Boolean) {
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            }
            val toneType = if (isDualSensor) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_ACK
            toneGenerator?.startTone(toneType, if (isDualSensor) 260 else 150)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(if (isDualSensor) 220L else 110L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v?.vibrate(android.os.VibrationEffect.createOneShot(if (isDualSensor) 220L else 110L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(if (isDualSensor) 220L else 110L)
                }
            }
        }
    }

    fun release() {
        stopLocationUpdates()
        stopOrientationSensor()
        stopMagnetometer()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
