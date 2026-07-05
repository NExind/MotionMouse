package com.motionmouse.app.settings

import com.motionmouse.app.connection.ConnectionType

/**
 * Immutable snapshot of all user-configurable motion and connection settings.
 *
 * Using an immutable data class means:
 *   - Settings can be passed safely across threads
 *   - The MotionEngine can hold a reference without worrying about
 *     concurrent mutation
 *   - Updates are applied atomically by replacing the entire object
 *
 * All values are validated at the repository layer before reaching here.
 * These ranges are the enforced limits — UI sliders should match them.
 */
data class MotionSettings(

    /**
     * Overall cursor speed multiplier.
     *
     * Applied after the acceleration curve.
     * Range: 0.1 (very slow) – 5.0 (very fast)
     * Default: 1.0 (tuned to feel natural on a 1080p screen)
     *
     * At sensitivity 1.0, a comfortable 90°/s rotation traverses
     * a 1920px screen in approximately 1 second.
     */
    val sensitivityX: Float = DEFAULT_SENSITIVITY,
    val sensitivityY: Float = DEFAULT_SENSITIVITY,

    /**
     * Exponential moving average factor for motion smoothing.
     *
     * Controls how much previous frames influence the current output.
     * Range: 0.0 (no smoothing, raw) – 0.95 (very heavy smoothing)
     * Default: 0.6 (removes jitter while keeping responsiveness)
     *
     * Higher values = smoother but adds latency feel.
     * Lower values = more responsive but jittery.
     *
     * Note: values above 0.95 make the cursor feel like it's
     * moving through mud and are clamped by MotionEngine.
     */
    val smoothingFactor: Float = DEFAULT_SMOOTHING,

    /**
     * Dead zone threshold in rad/s.
     *
     * Angular velocities below this value are treated as zero.
     * Prevents cursor drift when phone is stationary on desk.
     *
     * Range: 0.005 – 0.1 rad/s
     * Default: 0.02 rad/s (roughly 1.1°/s — filters vibration, not intent)
     *
     * Too low: cursor drifts from micro-vibrations.
     * Too high: cursor feels sticky and unresponsive at the start of movement.
     */
    val deadZone: Float = DEFAULT_DEAD_ZONE,

    /**
     * Exponent for the power-curve acceleration function.
     *
     * output = BASE_SCALE * sensitivity * sign(v) * |v|^exponent
     *
     * Range: 1.0 (linear) – 3.0 (very aggressive)
     * Default: 1.8 (mimics high-end desktop mouse acceleration)
     *
     * 1.0 = linear (same speed regardless of how fast you move)
     * 1.8 = natural feel, precise at low speed, fast at high speed
     * 2.5 = aggressive — very precise at low, very fast at high
     * 3.0 = extreme — hard to use, for power users only
     */
    val accelerationExponent: Float = DEFAULT_ACCELERATION_EXPONENT,

    /**
     * Preferred connection method.
     *
     * The connection manager will attempt this first.
     * Falls back automatically if unavailable.
     *
     * AUTO = let the system pick (Bluetooth preferred over Wi-Fi in V1).
     */
    val preferredConnection: ConnectionType = ConnectionType.AUTO,

    /**
     * Calibration offset for yaw zero point (rad/s).
     *
     * Set during calibration to cancel any constant gyro bias.
     * Applied before dead zone: corrected = raw - yawBias
     *
     * Most Android gyros have a small constant offset (bias) that
     * causes slow cursor drift without this correction.
     */
    val yawBias: Float = 0f,

    /**
     * Calibration offset for pitch zero point (rad/s).
     *
     * Applied before the complementary filter pitch rate input.
     * corrected = raw - pitchBias
     */
    val pitchBias: Float = 0f,

    /**
     * Whether to use hardware volume buttons as left/right click.
     *
     * Volume Up = Left click, Volume Down = Right click.
     * The physical click sounds/notifications are suppressed while active.
     */
    val useVolumeButtons: Boolean = false

) {
    companion object {
        const val DEFAULT_SENSITIVITY = 0.01f
        const val DEFAULT_SMOOTHING = 0.4f
        const val DEFAULT_DEAD_ZONE = 0.05f
        const val DEFAULT_ACCELERATION_EXPONENT = 1.8f

        // Validation ranges — enforced by SettingsRepository
        const val SENSITIVITY_MIN = 0.001f
        const val SENSITIVITY_MAX = 1.5f
        const val SMOOTHING_MIN = 0.0f
        const val SMOOTHING_MAX = 0.95f
        const val DEAD_ZONE_MIN = 0.005f
        const val DEAD_ZONE_MAX = 0.1f
        const val EXPONENT_MIN = 1.0f
        const val EXPONENT_MAX = 3.0f

        /** Returns a validated copy — clamps all values to legal ranges. */
        fun validated(
            sensitivityX: Float = DEFAULT_SENSITIVITY,
            sensitivityY: Float = DEFAULT_SENSITIVITY,
            smoothingFactor: Float = DEFAULT_SMOOTHING,
            deadZone: Float = DEFAULT_DEAD_ZONE,
            accelerationExponent: Float = DEFAULT_ACCELERATION_EXPONENT,
            preferredConnection: ConnectionType = ConnectionType.AUTO,
            yawBias: Float = 0f,
            pitchBias: Float = 0f,
            useVolumeButtons: Boolean = false
        ) = MotionSettings(
            sensitivityX = sensitivityX.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX),
            sensitivityY = sensitivityY.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX),
            smoothingFactor = smoothingFactor.coerceIn(SMOOTHING_MIN, SMOOTHING_MAX),
            deadZone = deadZone.coerceIn(DEAD_ZONE_MIN, DEAD_ZONE_MAX),
            accelerationExponent = accelerationExponent.coerceIn(EXPONENT_MIN, EXPONENT_MAX),
            preferredConnection = preferredConnection,
            yawBias = yawBias,
            pitchBias = pitchBias,
            useVolumeButtons = useVolumeButtons
        )
    }
}
