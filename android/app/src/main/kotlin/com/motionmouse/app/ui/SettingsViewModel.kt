package com.motionmouse.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.motion.MotionEngine
import com.motionmouse.app.motion.SensorFusion
import com.motionmouse.app.sensor.SensorManagerWrapper
import com.motionmouse.app.settings.MotionSettings
import com.motionmouse.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the settings screen.
 *
 * Loads current settings from the repository on init,
 * exposes them as editable state, and saves on every change.
 *
 * Save strategy: save immediately on every slider change.
 * Why not a Save button:
 *   - Sliders are continuous — the user expects live preview
 *   - Immediate save means settings survive app kill mid-edit
 *   - The motion engine updates in real-time so the user feels
 *     the effect of each change while the cursor is moving
 *
 * Calibration:
 *   Runs a 3-second collection window while the phone is stationary.
 *   Averages the gyro readings to find the resting bias offset.
 *   Saves the result directly to the repository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val motionEngine: MotionEngine,
    private val sensorWrapper: SensorManagerWrapper
) : ViewModel() {

    // Current settings snapshot — initialised from repository
    private val _settings = MutableStateFlow(MotionSettings())
    val settings: StateFlow<MotionSettings> = _settings.asStateFlow()

    // Calibration state
    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    init {
        // Load current settings from DataStore on init
        viewModelScope.launch {
            settingsRepository.settingsFlow
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = MotionSettings()
                )
                .collect { settings ->
                    _settings.value = settings
                }
        }
    }

    // --- Individual setting update functions ---
    // Each saves immediately so the motion engine picks up the change
    // in real-time via the settings flow in the foreground service.

    fun setSensitivityX(value: Float) {
        updateAndSave(_settings.value.copy(sensitivityX = value))
    }

    fun setSensitivityY(value: Float) {
        updateAndSave(_settings.value.copy(sensitivityY = value))
    }

    fun setSmoothing(value: Float) {
        updateAndSave(_settings.value.copy(smoothingFactor = value))
    }

    fun setDeadZone(value: Float) {
        updateAndSave(_settings.value.copy(deadZone = value))
    }

    fun setAccelerationExponent(value: Float) {
        updateAndSave(_settings.value.copy(accelerationExponent = value))
    }

    fun setPreferredConnection(type: ConnectionType) {
        updateAndSave(_settings.value.copy(preferredConnection = type))
    }

    fun setUseVolumeButtons(enabled: Boolean) {
        updateAndSave(_settings.value.copy(useVolumeButtons = enabled))
    }

    private fun updateAndSave(newSettings: MotionSettings) {
        _settings.value = newSettings
        viewModelScope.launch {
            settingsRepository.saveSettings(newSettings)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            // _settings will update automatically via the flow collector above
        }
    }

    // --- Calibration ---

    /**
     * Run a calibration sequence.
     *
     * Instructs the user to place the phone flat and still.
     * Collects gyroscope readings for CALIBRATION_DURATION_MS.
     * Averages them to find the constant bias offset.
     * Saves the bias to settings so MotionEngine can subtract it.
     *
     * The gyro bias cancels any constant drift the sensor has
     * at room temperature and resting position.
     *
     * Note: We collect the SensorFusion's raw output indirectly
     * via a short sampling window on the motionOutput flow.
     * A more precise implementation would add a dedicated
     * calibration mode to SensorManagerWrapper — that is a
     * straightforward future improvement.
     */
    fun startCalibration() {
        if (_calibrationState.value is CalibrationState.Running) return

        viewModelScope.launch {
            _calibrationState.value = CalibrationState.Running(progress = 0f)

            // Collect samples over the calibration window
            val samples = mutableListOf<Pair<Float, Float>>() // yaw, pitch velocity
            val startTime = System.currentTimeMillis()
            val duration = CALIBRATION_DURATION_MS

            // Sample the motion output at ~50Hz for the duration
            // We use a simple polling loop here for clarity
            while (System.currentTimeMillis() - startTime < duration) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = elapsed.toFloat() / duration

                _calibrationState.value = CalibrationState.Running(progress = progress)

                // Read current raw velocities from SensorFusion via sensorWrapper
                // These represent the drift we want to cancel
                val yawVel = sensorWrapper.getCurrentYawVelocity()
                val pitchVel = sensorWrapper.getCurrentPitchVelocity()

                if (yawVel != null && pitchVel != null) {
                    samples.add(Pair(yawVel, pitchVel))
                }

                kotlinx.coroutines.delay(20) // ~50Hz sampling
            }

            if (samples.isEmpty()) {
                _calibrationState.value = CalibrationState.Failed(
                    reason = "No sensor data received. Check sensor permissions."
                )
                return@launch
            }

            // Average the samples to find the bias
            val yawBias = samples.map { it.first }.average().toFloat()
            val pitchBias = samples.map { it.second }.average().toFloat()

            // Save calibration
            settingsRepository.saveCalibration(yawBias, pitchBias)

            _calibrationState.value = CalibrationState.Complete(
                yawBias = yawBias,
                pitchBias = pitchBias
            )

            // Return to idle after showing result briefly
            kotlinx.coroutines.delay(2000)
            _calibrationState.value = CalibrationState.Idle
        }
    }

    fun cancelCalibration() {
        _calibrationState.value = CalibrationState.Idle
    }

    companion object {
        private const val CALIBRATION_DURATION_MS = 3000L
    }
}

/**
 * All possible states of the calibration flow.
 */
sealed class CalibrationState {
    object Idle : CalibrationState()
    data class Running(val progress: Float) : CalibrationState()
    data class Complete(val yawBias: Float, val pitchBias: Float) : CalibrationState()
    data class Failed(val reason: String) : CalibrationState()
}
