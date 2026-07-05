package com.motionmouse.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motionmouse.app.connection.ConnectionManager
import com.motionmouse.app.connection.ConnectionState
import com.motionmouse.app.connection.DiscoveredHost
import com.motionmouse.app.motion.MotionEngine
import com.motionmouse.app.protocol.PacketBuilder
import com.motionmouse.app.sensor.SensorManagerWrapper
import com.motionmouse.app.sensor.SensorStatus
import com.motionmouse.app.service.MotionMouseService
import com.motionmouse.app.settings.MotionSettings
import com.motionmouse.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the main screen.
 *
 * Bridges the connection manager, motion engine, and sensor wrapper
 * to the UI. All UI state is derived from the shared singletons
 * that the foreground service also uses — the UI is an observer,
 * not an owner, of the motion pipeline.
 *
 * The ViewModel survives configuration changes (rotation, etc.)
 * but does NOT own the pipeline — that's the service's job.
 * If the ViewModel is cleared, the pipeline keeps running.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val motionEngine: MotionEngine,
    private val sensorWrapper: SensorManagerWrapper,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // --- Exposed state flows ---

    val connectionState: StateFlow<ConnectionState> =
        connectionManager.connectionState

    val discoveredHosts: StateFlow<List<DiscoveredHost>> =
        connectionManager.discoveredHosts

    val latencyMs: StateFlow<Long> =
        connectionManager.latencyMs

    val sensorStatus: StateFlow<SensorStatus> =
        sensorWrapper.sensorStatus

    // Whether the cursor lock is currently active
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    // Current settings — used by MainActivity for volume key interception
    val currentSettings: StateFlow<MotionSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MotionSettings()
        )

    /**
     * Combined UI state — everything the main screen needs in one object.
     * Recomputed whenever any upstream flow changes.
     */
    val uiState: StateFlow<MainUiState> = combine(
        connectionState,
        discoveredHosts,
        latencyMs,
        isLocked,
        sensorStatus
    ) { connState, hosts, latency, locked, sensor ->
        MainUiState(
            connectionState = connState,
            discoveredHosts = hosts,
            latencyMs = latency,
            isLocked = locked,
            sensorStatus = sensor,
            isConnected = connState is ConnectionState.Connected,
            pcName = (connState as? ConnectionState.Connected)?.pcName ?: "",
            connectionTypeName = (connState as? ConnectionState.Connected)
                ?.connectionType?.displayName() ?: ""
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    // --- Service management ---

    /**
     * Ensure the foreground service is running.
     * Called from MainActivity.onStart() — idempotent.
     */
    fun ensureServiceRunning() {
        val intent = Intent(context, MotionMouseService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // --- Connection actions ---

    fun connectTo(host: DiscoveredHost) {
        connectionManager.connectTo(host)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun startDiscovery() {
        connectionManager.startDiscovery()
    }

    // --- Button actions ---

    /**
     * Left button pressed — send press packet immediately.
     * Haptic feedback confirms the action to the user.
     */
    fun onLeftButtonDown() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendButtonPacket(PacketBuilder.buildLeftPress())
        }
        triggerHaptic(HapticStyle.CLICK)
    }

    fun onLeftButtonUp() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendButtonPacket(PacketBuilder.buildLeftRelease())
        }
    }

    fun onRightButtonDown() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendButtonPacket(PacketBuilder.buildRightPress())
        }
        triggerHaptic(HapticStyle.CLICK)
    }

    fun onRightButtonUp() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendButtonPacket(PacketBuilder.buildRightRelease())
        }
    }

    // --- Lock toggle ---

    /**
     * Toggle cursor lock.
     * When locked, the motion engine emits zero velocity.
     * A distinct haptic confirms the state change.
     */
    fun toggleLock() {
        val newLocked = !_isLocked.value
        _isLocked.value = newLocked
        motionEngine.setLocked(newLocked)
        connectionManager.isLocked = newLocked
        triggerHaptic(if (newLocked) HapticStyle.LOCK else HapticStyle.UNLOCK)
    }

    // --- Haptic feedback ---

    /**
     * Trigger device vibration for tactile feedback.
     *
     * Uses VibrationEffect (API 26+) for precise control.
     * Falls back to legacy vibrate() on older devices.
     *
     * Three distinct patterns:
     *   CLICK  — short crisp pulse for button presses
     *   LOCK   — double pulse for lock engage
     *   UNLOCK — single medium pulse for lock release
     *
     * Amplitudes use predefined constants where available
     * so the OS can optimise for the device's actuator.
     */
    private fun triggerHaptic(style: HapticStyle) {
        viewModelScope.launch {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                            as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = when (style) {
                        HapticStyle.CLICK -> VibrationEffect.createOneShot(
                            30L,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                        HapticStyle.LOCK -> VibrationEffect.createWaveform(
                            longArrayOf(0, 40, 60, 40),   // delay, on, off, on
                            intArrayOf(0, 180, 0, 120),    // amplitudes
                            -1                             // no repeat
                        )
                        HapticStyle.UNLOCK -> VibrationEffect.createOneShot(
                            60L,
                            120
                        )
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    when (style) {
                        HapticStyle.CLICK -> vibrator.vibrate(30L)
                        HapticStyle.LOCK -> vibrator.vibrate(longArrayOf(0, 40, 60, 40), -1)
                        HapticStyle.UNLOCK -> vibrator.vibrate(60L)
                    }
                }
            } catch (e: Exception) {
                // Vibration is non-critical — ignore all errors silently
            }
        }
    }

    private enum class HapticStyle { CLICK, LOCK, UNLOCK }
}

/**
 * Snapshot of all state the main screen needs to render.
 * Immutable — produced fresh on every state change.
 */
data class MainUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val latencyMs: Long = 0L,
    val isLocked: Boolean = false,
    val sensorStatus: SensorStatus = SensorStatus.UNKNOWN,
    val isConnected: Boolean = false,
    val pcName: String = "",
    val connectionTypeName: String = ""
)
