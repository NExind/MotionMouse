package com.motionmouse.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.motionmouse.app.connection.ConnectionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore extension property — one instance per application.
private val Context.dataStore: DataStore<Preferences>
    by preferencesDataStore(name = "motion_mouse_settings")

/**
 * Persists and retrieves MotionSettings using Jetpack DataStore.
 *
 * DataStore is the modern replacement for SharedPreferences:
 *   - Coroutine and Flow based (no callback hell)
 *   - Safe from main-thread I/O violations
 *   - Atomic writes (no partial saves)
 *   - No ANR risk unlike SharedPreferences.commit()
 *
 * All writes validate before persisting.
 * All reads apply defaults for missing keys (fresh install).
 *
 * Injected as a Singleton — one instance for the whole app lifetime.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // --- DataStore preference keys ---
    // Using typed keys prevents runtime ClassCastExceptions.

    private object Keys {
        val SENSITIVITY_X = floatPreferencesKey("sensitivityX")
        val SENSITIVITY_Y = floatPreferencesKey("sensitivityY")
        val SMOOTHING_FACTOR = floatPreferencesKey("smoothing_factor")
        val DEAD_ZONE = floatPreferencesKey("dead_zone")
        val ACCELERATION_EXPONENT = floatPreferencesKey("acceleration_exponent")
        val PREFERRED_CONNECTION = stringPreferencesKey("preferred_connection")
        val YAW_BIAS = floatPreferencesKey("yaw_bias")
        val PITCH_BIAS = floatPreferencesKey("pitch_bias")
        val USE_VOLUME_BUTTONS = booleanPreferencesKey("use_volume_buttons")
    }

    /**
     * Observe settings as a Flow.
     *
     * Emits the current settings immediately on collection,
     * then emits again whenever any setting changes.
     *
     * The MotionEngine observes this in the foreground service
     * and calls updateSettings() on each emission.
     */
    val settingsFlow: Flow<MotionSettings> = context.dataStore.data
        .map { preferences ->
            // Provide a migration path for old sensitivity key
            val oldSensitivity = preferences[floatPreferencesKey("sensitivity")]
            
            val sensitivityX = preferences[Keys.SENSITIVITY_X] 
                ?: oldSensitivity 
                ?: MotionSettings.DEFAULT_SENSITIVITY
                
            val sensitivityY = preferences[Keys.SENSITIVITY_Y] 
                ?: oldSensitivity 
                ?: MotionSettings.DEFAULT_SENSITIVITY

            MotionSettings.validated(
                sensitivityX = sensitivityX,
                sensitivityY = sensitivityY,
                smoothingFactor = preferences[Keys.SMOOTHING_FACTOR]
                    ?: MotionSettings.DEFAULT_SMOOTHING,
                deadZone = preferences[Keys.DEAD_ZONE]
                    ?: MotionSettings.DEFAULT_DEAD_ZONE,
                accelerationExponent = preferences[Keys.ACCELERATION_EXPONENT]
                    ?: MotionSettings.DEFAULT_ACCELERATION_EXPONENT,
                preferredConnection = preferences[Keys.PREFERRED_CONNECTION]
                    ?.let { runCatching { ConnectionType.valueOf(it) }.getOrNull() }
                    ?: ConnectionType.AUTO,
                yawBias = preferences[Keys.YAW_BIAS] ?: 0f,
                pitchBias = preferences[Keys.PITCH_BIAS] ?: 0f,
                useVolumeButtons = preferences[Keys.USE_VOLUME_BUTTONS] ?: false
            )
        }

    /**
     * Persist a complete settings snapshot.
     * Validates all values before writing.
     * Safe to call from any coroutine context.
     */
    suspend fun saveSettings(settings: MotionSettings) {
        val validated = MotionSettings.validated(
            sensitivityX = settings.sensitivityX,
            sensitivityY = settings.sensitivityY,
            smoothingFactor = settings.smoothingFactor,
            deadZone = settings.deadZone,
            accelerationExponent = settings.accelerationExponent,
            preferredConnection = settings.preferredConnection,
            yawBias = settings.yawBias,
            pitchBias = settings.pitchBias,
            useVolumeButtons = settings.useVolumeButtons
        )

        context.dataStore.edit { preferences ->
            // Clear the old key if it exists so we don't carry it around forever
            preferences.remove(floatPreferencesKey("sensitivity"))
            
            preferences[Keys.SENSITIVITY_X] = validated.sensitivityX
            preferences[Keys.SENSITIVITY_Y] = validated.sensitivityY
            preferences[Keys.SMOOTHING_FACTOR] = validated.smoothingFactor
            preferences[Keys.DEAD_ZONE] = validated.deadZone
            preferences[Keys.ACCELERATION_EXPONENT] = validated.accelerationExponent
            preferences[Keys.PREFERRED_CONNECTION] = validated.preferredConnection.name
            preferences[Keys.YAW_BIAS] = validated.yawBias
            preferences[Keys.PITCH_BIAS] = validated.pitchBias
            preferences[Keys.USE_VOLUME_BUTTONS] = validated.useVolumeButtons
        }
    }

    /**
     * Save calibration biases independently.
     *
     * Called after calibration completes — doesn't touch other settings.
     * This is important because calibration runs as a separate flow
     * and we don't want to accidentally overwrite settings the user
     * changed during calibration.
     */
    suspend fun saveCalibration(yawBias: Float, pitchBias: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.YAW_BIAS] = yawBias
            preferences[Keys.PITCH_BIAS] = pitchBias
        }
    }

    /**
     * Reset all settings to factory defaults.
     * Called from the settings screen "Reset to defaults" option.
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * Apply a SETTINGS_SYNC packet received from the Windows app.
     *
     * The Windows app can push sensitivity, smoothing, dead zone,
     * and acceleration exponent. It cannot change connection type
     * or calibration from the PC side.
     */
    suspend fun applyRemoteSync(
        sensitivityX: Float,
        sensitivityY: Float,
        smoothingFactor: Float,
        deadZone: Float,
        accelerationExponent: Float
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SENSITIVITY_X] =
                sensitivityX.coerceIn(MotionSettings.SENSITIVITY_MIN, MotionSettings.SENSITIVITY_MAX)
            preferences[Keys.SENSITIVITY_Y] =
                sensitivityY.coerceIn(MotionSettings.SENSITIVITY_MIN, MotionSettings.SENSITIVITY_MAX)
            preferences[Keys.SMOOTHING_FACTOR] =
                smoothingFactor.coerceIn(MotionSettings.SMOOTHING_MIN, MotionSettings.SMOOTHING_MAX)
            preferences[Keys.DEAD_ZONE] =
                deadZone.coerceIn(MotionSettings.DEAD_ZONE_MIN, MotionSettings.DEAD_ZONE_MAX)
            preferences[Keys.ACCELERATION_EXPONENT] =
                accelerationExponent.coerceIn(MotionSettings.EXPONENT_MIN, MotionSettings.EXPONENT_MAX)
        }
    }
}
