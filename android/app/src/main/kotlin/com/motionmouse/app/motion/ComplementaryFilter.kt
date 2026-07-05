package com.motionmouse.app.motion

import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Complementary Filter for sensor fusion.
 *
 * Combines gyroscope (fast, noisy short-term) with accelerometer
 * (slow, stable long-term) to produce drift-free pitch estimation.
 *
 * As discussed: this only corrects PITCH (tilt forward/backward).
 * YAW (rotation around gravity axis) cannot be corrected by accelerometer —
 * it is handled separately in GyroscopeProcessor with dead zones and smoothing.
 *
 * The filter works by trusting the gyroscope for fast movements
 * (where it is accurate) and trusting the accelerometer for slow drift
 * correction (where the gyro accumulates error).
 *
 * Alpha controls the blend:
 *   Alpha close to 1.0 → trust gyro more (responsive, more drift)
 *   Alpha close to 0.0 → trust accel more (stable, sluggish response)
 *
 * A value of 0.96 is a well-established default for 100Hz sensor rate.
 * At 100Hz, this gives a crossover frequency of ~0.64Hz — meaning
 * movements faster than 0.64Hz use the gyro, slower corrections
 * come from the accelerometer.
 */
class ComplementaryFilter(
    private var alpha: Float = DEFAULT_ALPHA
) {

    companion object {
        // At 100Hz sample rate, alpha = 0.96 gives ~0.64Hz crossover.
        // Derived from: alpha = tau / (tau + dt)
        // where tau = 0.24s (time constant), dt = 0.01s (100Hz)
        const val DEFAULT_ALPHA = 0.96f

        // Minimum time delta to consider valid — guards against
        // duplicate sensor events or clock glitches.
        const val MIN_DELTA_SECONDS = 0.0001f

        // Maximum time delta — if larger, sensor was probably paused.
        // We clamp rather than integrate a huge step.
        const val MAX_DELTA_SECONDS = 0.05f  // 50ms = 20Hz minimum
    }

    // Current filtered pitch angle in radians.
    // Positive = front edge lifted (cursor moves up).
    // Negative = back edge lifted (cursor moves down).
    var pitchAngle: Float = 0f
        private set

    // Whether the filter has been initialized with a first reading.
    private var isInitialized: Boolean = false

    /**
     * Update the complementary filter with new sensor data.
     *
     * @param gyroPitchRate  Angular velocity around X axis (rad/s).
     *                       Positive = front edge lifting.
     * @param accelX         Accelerometer X component (m/s²)
     * @param accelY         Accelerometer Y component (m/s²)
     * @param accelZ         Accelerometer Z component (m/s²)
     * @param deltaSeconds   Time since last update (seconds)
     */
    fun update(
        gyroPitchRate: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        deltaSeconds: Float
    ) {
        // Guard against degenerate time deltas.
        val dt = deltaSeconds.coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)

        // Compute pitch from accelerometer using gravity vector.
        // When phone is flat on desk:
        //   accelZ ≈ 9.8 (gravity pulling through screen)
        //   accelY ≈ 0
        //   accelX ≈ 0
        //
        // When front edge lifts:
        //   accelY increases (gravity component along Y axis)
        //   accelZ decreases
        //
        // atan2(y, z) gives us the angle of tilt in the YZ plane.
        // We normalise by the magnitude to be robust against
        // linear acceleration during movement.
        val magnitude = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)

        // If magnitude is near zero, the accelerometer reading is invalid
        // (extreme linear acceleration, sensor fault). Skip accel correction.
        val accelPitch = if (magnitude > 0.5f) {
            atan2(accelY, accelZ)
        } else {
            pitchAngle  // Fall back to current estimate
        }

        if (!isInitialized) {
            // Seed the filter with the accelerometer reading so we start
            // at the correct angle rather than always from zero.
            pitchAngle = accelPitch
            isInitialized = true
            return
        }

        // Complementary filter:
        // New estimate = alpha * (old estimate + gyro integration)
        //              + (1 - alpha) * accelerometer estimate
        //
        // The gyro term integrates angular velocity to get angle change.
        // The accel term provides slow long-term correction.
        pitchAngle = alpha * (pitchAngle + gyroPitchRate * dt) +
                (1f - alpha) * accelPitch
    }

    /**
     * Recalculate alpha when sensor rate changes.
     * tau = 0.24 seconds is the time constant (stay the same).
     * alpha = tau / (tau + dt)
     */
    fun setSampleRate(hz: Float) {
        val dt = 1f / hz
        val tau = 0.24f
        alpha = tau / (tau + dt)
    }

    /**
     * Reset filter state — call on calibration or reconnect.
     */
    fun reset() {
        pitchAngle = 0f
        isInitialized = false
    }
}
