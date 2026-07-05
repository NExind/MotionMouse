package com.motionmouse.app.motion

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * SensorFusion combines raw gyroscope and accelerometer readings
 * into clean, usable motion values for the cursor.
 *
 * Responsibilities:
 *  - Run the complementary filter for pitch
 *  - Integrate gyro yaw independently (no accel correction possible)
 *  - Extract angular velocities in the axes we care about
 *  - Apply sensor coordinate system remapping for the phone's orientation
 *
 * Coordinate system (phone lying flat, screen up, top pointing away):
 *
 *   X axis → points right (across the phone width)
 *   Y axis → points toward the top of the phone
 *   Z axis → points up through the screen
 *
 * Cursor mapping:
 *   Yaw   (rotation around Z) → cursor X (left/right)
 *   Pitch (rotation around X) → cursor Y (up/down)
 *   Roll  (rotation around Y) → unused
 *
 * For yaw: rotating phone clockwise (viewed from above) = positive Z angular velocity
 *          → cursor moves right.
 *
 * For pitch: lifting front edge = positive X angular velocity (top moves away, bottom stays)
 *            Actually the sign depends on sensor orientation.
 *            We expose a pitchMultiplier to correct this without changing logic.
 */
class SensorFusion {

    companion object {
        // Allows sign flip for pitch if hardware reports opposite convention.
        // This is adjustable via calibration rather than hardcoded.
        const val DEFAULT_PITCH_MULTIPLIER = 1f
        const val DEFAULT_YAW_MULTIPLIER = -1f
    }

    private val complementaryFilter = ComplementaryFilter()

    // Timestamp of last sensor update in nanoseconds.
    private var lastTimestampNanos: Long = 0L

    // Previous pitch angle — we differentiate to get pitch velocity
    // (rate of change of angle, not raw gyro pitch rate).
    // Using the filtered angle rather than raw gyro gives us
    // the complementary filter's stability benefit in velocity too.
    private var previousPitchAngle: Float = 0f

    // Configurable sign multipliers set during calibration.
    var pitchMultiplier: Float = DEFAULT_PITCH_MULTIPLIER
    var yawMultiplier: Float = DEFAULT_YAW_MULTIPLIER

    // Output: angular velocities after fusion, in rad/s.
    // These are what MotionEngine reads to produce cursor movement.
    var yawVelocity: Float = 0f
        private set
    var pitchVelocity: Float = 0f
        private set

    // Expose current filtered pitch for calibration UI display.
    val currentPitchAngle: Float get() = complementaryFilter.pitchAngle

    /**
     * Called by SensorManagerWrapper on each combined sensor event.
     *
     * @param gyroX   Gyro X axis (rad/s) — pitch rate (around X axis)
     * @param gyroY   Gyro Y axis (rad/s) — roll rate (unused)
     * @param gyroZ   Gyro Z axis (rad/s) — yaw rate (around Z axis)
     * @param accelX  Accelerometer X (m/s²)
     * @param accelY  Accelerometer Y (m/s²)
     * @param accelZ  Accelerometer Z (m/s²)
     * @param timestampNanos  Event timestamp in nanoseconds (from sensor event)
     */
    fun update(
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        timestampNanos: Long
    ) {
        // Compute delta time in seconds from nanosecond timestamps.
        // Using sensor timestamps rather than wall clock is critical —
        // sensor timestamps are monotonic and accurately reflect the
        // actual sample interval, which is more precise than System.nanoTime().
        val deltaSeconds = if (lastTimestampNanos == 0L) {
            0.01f  // Assume 100Hz for first frame
        } else {
            ((timestampNanos - lastTimestampNanos) / 1_000_000_000.0).toFloat()
        }
        lastTimestampNanos = timestampNanos

        // --- PITCH (cursor Y) ---
        // Run complementary filter: combines gyro pitch rate with accel tilt.
        // Phone lying flat: X axis is across the phone, so gyroX = pitch rate.
        complementaryFilter.update(
            gyroPitchRate = gyroX,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            deltaSeconds = deltaSeconds
        )

        // Derive pitch velocity by differentiating the filtered angle.
        // This gives us a velocity that has the stability of the complementary
        // filter rather than the raw (noisy) gyro pitch rate.
        val filteredPitch = complementaryFilter.pitchAngle
        pitchVelocity = if (deltaSeconds > 0f) {
            ((filteredPitch - previousPitchAngle) / deltaSeconds) * pitchMultiplier
        } else {
            0f
        }
        previousPitchAngle = filteredPitch

        // --- YAW (cursor X) ---
        // gyroZ is the angular velocity around the Z axis (perpendicular to screen).
        // Rotating clockwise (viewed from above) = cursor right.
        // No complementary correction possible — gyro only.
        // Dead zone and smoothing in MotionEngine will handle micro-drift.
        yawVelocity = gyroZ * yawMultiplier
    }

    /**
     * Update the complementary filter's alpha for a new sample rate.
     */
    fun setSampleRate(hz: Float) {
        complementaryFilter.setSampleRate(hz)
    }

    /**
     * Reset all state — call on calibration or service restart.
     */
    fun reset() {
        complementaryFilter.reset()
        lastTimestampNanos = 0L
        previousPitchAngle = 0f
        yawVelocity = 0f
        pitchVelocity = 0f
    }
}
