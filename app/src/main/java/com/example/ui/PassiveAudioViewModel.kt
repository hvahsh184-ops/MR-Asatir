package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.domain.PassiveMaterialId
import com.example.sensors.AudioHeadphonePassivePipeline
import com.example.sensors.AudioPassivePipelineState
import kotlinx.coroutines.flow.StateFlow

/**
 * Dedicated isolated ViewModel for 192kHz passive audio/VLF monitoring.
 * Decoupled from RadarViewModel so high-frequency STFT emissions (~20-22 Hz)
 * never trigger recompositions or lock contention on the main radar/SAR/map feed.
 */
class PassiveAudioViewModel(application: Application) : AndroidViewModel(application) {
    private val pipeline = AudioHeadphonePassivePipeline(application)

    val uiState: StateFlow<AudioPassivePipelineState> = pipeline.state

    fun startListening() = pipeline.startListening()

    fun stopListening() = pipeline.stopListening()

    fun startCalibration() = pipeline.startCalibration()

    fun setGainMultiplier(gain: Float) = pipeline.setGainMultiplier(gain)

    fun toggleMaterial(material: PassiveMaterialId) = pipeline.toggleMaterial(material)

    fun selectAllMaterials() = pipeline.selectAllMaterials()

    fun selectNonFerrousOnly() = pipeline.selectNonFerrousOnly()

    override fun onCleared() {
        super.onCleared()
        pipeline.release()
    }
}
