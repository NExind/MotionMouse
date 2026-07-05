package com.motionmouse.app.motion

import com.motionmouse.app.settings.MotionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MotionEngine is the core of Motion Mouse.
 *
 * It receives fused angular velocity values from SensorFusion and
 * transforms them into cursor delta values ready to send to the PC.
 *
 * Pipeline (per sensor update):
 *
 *   Raw angular velocities (rad/s)
 *     ↓
 *   Dead zone filtering       — suppress micro-movements below threshold
 *     ↓
 *   Exponential smoothing     — reduce jitter without adding lag
 *     ↓
 *   Adaptive acceleration     — non-linear power curve (slow=precise, fast=fast)
 *     ↓
 *   Sensitivity scaling       — user-configurable multiplier
 *     ↓
 *   Lock gate                 — zero output when user has locked cursor
 *     ↓
 *   Cursor delta (pixels/sec) → ready to transmit
 *
 * Design notes:
 *
 * We output VELOCITY (pixels per second), not pixel deltas.
 * The Windows side multiplies by elapsed time to get actual pixel movement.
 * This makes the system robust to network jitter — if a packet arrives
 * late, the cursor still moves the correct distance.
 *
 * The acceleration curve uses a power function:
 *   output = sensitivity * sign(v) * |v|^exponent
 *
 * Where v is the dead-zone-filtered, smoothed angular velocity.
 * The exponent (default 1.8) produces:
 *   - Very precise control at low speeds (sub-linear feel at low v)
 *   - Natural acceleration at medium speeds
 *   - Fast traversal at high speeds
 *
 * This mimics the acceleration curve of high-end desktop mice.
 */
class MotionEngine(
    private var settings: MotionSettings
) {

    companion object {
        // Pixels per second per (rad/s) at sensitivity = 1.0, exponent = 1.0.
        // This base scalar converts angular velocity to screen velocity.
        // Tuned so that a comfortable wrist rotation covers the screen width.
        // At 1920px wide: rotating ~90°/s ≈ 1.57 rad/s should traverse screen in ~1s.
        // BASE_SCALE = 1920 / 1.57 ≈ 1222. We use 1200 as a round number.
        private const val BASE_SCALE = 1200f

        // Smoothing bounds — prevents settings from making cursor unusable.
        private const val MIN_SMOOTHING = 0.0f   // No smoothing (raw)
        private const val MAX_SMOOTHING = 0.95f  // Heavy smoothing (very sluggish if higher)

        // Acceleration exponent bounds.
        private const val MIN_EXPONENT = 1.0f    // Linear
        private const val MAX_EXPONENT = 3.0f    // Very aggressive curve
    }

    // Whether the user has locked the cursor via the lock button.
    private var isLocked: Boolean = false

    // Exponential moving average state for X and Y independently.
    // EMA smooths jitter while preserving responsiveness better than
    // a simple moving average because it weights recent samples more.
    private var smoothedYaw: Float = 0f
    private var smoothedPitch: Float = 0f

    // Whether smoothing state has been seeded (prevents jump on first frame).
    private var smoothingInitialized: Boolean = false

    // Expose current output as StateFlow for the UI to observe latency/status.
    private val _motionOutput = MutableStateFlow(MotionOutput(0f, 0f, 0L))
    val motionOutput: StateFlow<MotionOutput> = _motionOutput.asStateFlow()

    /**
     * Process new angular velocities from SensorFusion.
     * Called at sensor rate (target 100–200Hz).
     *
     * @param yawVelocity   Rotation around Z (rad/s) → cursor X
     * @param pitchVelocity Rate of pitch change (rad/s) → cursor Y
     * @param timestampMs   Current time in milliseconds
     * @return MotionOutput containing cursor velocity in pixels/sec,
     *         or zero if locked or below dead zone.
     */
    fun process(
        yawVelocity: Float,
        pitchVelocity: Float,
        timestampMs: Long
    ): MotionOutput {

        // If locked, emit zero immediately — no processing needed.
        if (isLocked) {
            return MotionOutput(0f, 0f, timestampMs).also {
                _motionOutput.value = it
            }
        }

        // Step 1: Dead zone
        // Suppress any input below the dead zone threshold.
        // The dead zone is defined in rad/s — below this, the phone is
        // considered stationary. This eliminates cursor drift from
        // micro-vibrations and gyro noise when phone is at rest.
        val deadZone = settings.deadZone
        val filteredYaw = applyDeadZone(yawVelocity, deadZone)
        val filteredPitch = applyDeadZone(pitchVelocity, deadZone)

        // Step 2: Exponential moving average smoothing
        // EMA: smoothed = alpha * previous + (1 - alpha) * current
        // where alpha = smoothingFactor (0 = no smoothing, 1 = frozen).
        //
        // We apply smoothing AFTER the dead zone so that the
        // EMA doesn't slowly decay toward zero after motion stops —
        // the dead zone ensures the input is already zero when stopped.
        val smoothing = settings.smoothingFactor.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING)
        val (smoothYaw, smoothPitch) = applySmoothing(filteredYaw, filteredPitch, smoothing)

        // Step 3: Adaptive acceleration (power curve)
        // output_x = BASE_SCALE * sign(v) * |v|^exponent * sensitivity
        //
        // The power curve ensures:
        //   At low v (precise movement): output is sub-linear → fine control
        //   At high v (fast sweep): output is super-linear → fast traversal
        //
        // We normalise the input velocity before the power curve so that
        // the sensitivity setting feels consistent across different exponents.
        val exponent = settings.accelerationExponent.coerceIn(MIN_EXPONENT, MAX_EXPONENT)
        val sensitivityX = settings.sensitivityX
        val sensitivityY = settings.sensitivityY

        val cursorVelocityX = applyAcceleration(smoothYaw, exponent, sensitivityX)
        val cursorVelocityY = applyAcceleration(smoothPitch, exponent, sensitivityY)

        // Invert Y if needed (screen Y increases downward, pitch up = cursor up).
        // Lifting front edge = positive pitch velocity = cursor moves UP = negative screen Y.
        val finalX = cursorVelocityX
        val finalY = -cursorVelocityY  // Invert: pitch up → cursor up

        val output = MotionOutput(
            deltaX = finalX,
            deltaY = finalY,
            timestampMs = timestampMs
        )

        _motionOutput.value = output
        return output
    }

    /**
     * Apply a dead zone with smooth re-entry.
     *
     * Rather than a hard cutoff (which causes a jarring jump as the cursor
     * suddenly starts moving), we use a soft dead zone:
     * - Below threshold: zero
     * - Above threshold: smoothly ramp from zero using rescaled value
     *
     * This is the same technique used in gamepad stick processing.
     *
     * rescaled = (|v| - deadZone) / (1 - deadZone) * sign(v)
     *
     * This maps:
     *   deadZone → 0
     *   1.0 rad/s → 1.0 rad/s
     * Giving a smooth transition at the dead zone boundary.
     */
    private fun applyDeadZone(velocity: Float, deadZone: Float): Float {
        val absV = abs(velocity)
        if (absV <= deadZone) return 0f

        // Rescale so motion starts smoothly from zero at the dead zone edge.
        val rescaled = (absV - deadZone) / (1f - deadZone.coerceAtMost(0.99f))
        return rescaled * sign(velocity)
    }

    /**
     * Apply exponential moving average to both axes.
     * Initialises smoothing state on first non-zero input.
     */
    private fun applySmoothing(
        yaw: Float,
        pitch: Float,
        smoothing: Float
    ): Pair<Float, Float> {
        if (!smoothingInitialized) {
            smoothedYaw = yaw
            smoothedPitch = pitch
            smoothingInitialized = true
        } else {
            smoothedYaw = smoothing * smoothedYaw + (1f - smoothing) * yaw
            smoothedPitch = smoothing * smoothedPitch + (1f - smoothing) * pitch
        }
        return Pair(smoothedYaw, smoothedPitch)
    }

    /**
     * Apply non-linear power curve acceleration.
     *
     * output = BASE_SCALE * sensitivity * sign(v) * |v|^exponent
     *
     * The input v is in rad/s (post dead zone, post smoothing).
     * The output is in pixels/second.
     *
     * Why this formula:
     *   At v = 0.1 rad/s, exponent 1.8: output ≈ 0.016 → tiny precise movement
     *   At v = 0.5 rad/s, exponent 1.8: output ≈ 0.287 → comfortable medium speed
     *   At v = 1.5 rad/s, exponent 1.8: output ≈ 1.86  → fast traversal
     *
     * Multiplied by BASE_SCALE (1200) and sensitivity (default 1.0):
     *   0.016 * 1200 =  19 px/s  (very precise)
     *   0.287 * 1200 = 344 px/s  (medium)
     *   1.86  * 1200 = 2232 px/s (fast, covers 1920px in ~0.86s — feels right)
     */
    private fun applyAcceleration(
        velocity: Float,
        exponent: Float,
        sensitivity: Float
    ): Float {
        if (velocity == 0f) return 0f
        val absV = abs(velocity)
        val accelerated = absV.pow(exponent)
        return BASE_SCALE * sensitivity * sign(velocity) * accelerated
    }

    // --- Public control API ---

    /**
     * Toggle cursor lock state.
     * When locked, all motion output is zeroed.
     * Smoothing state is reset on unlock to prevent accumulated EMA
     * from causing a jump when motion resumes.
     */
    fun setLocked(locked: Boolean) {
        isLocked = locked
        if (!locked) {
            // Reset smoothing when unlocking so stale EMA doesn't cause
            // a jump on the first frame of motion.
            smoothingInitialized = false
        }
    }

    fun isLocked(): Boolean = isLocked

    /**
     * Apply new settings (called when user adjusts settings or
     * Windows pushes a SETTINGS_SYNC packet).
     */
    fun updateSettings(newSettings: MotionSettings) {
        settings = newSettings
        // Reset smoothing when settings change to avoid artifacts
        // from the old smoothing factor bleeding into new state.
        smoothingInitialized = false
    }

    /**
     * Reset all state — call on calibration or service restart.
     */
    fun reset() {
        smoothedYaw = 0f
        smoothedPitch = 0f
        smoothingInitialized = false
        isLocked = false
    }
}

/**
 * Output of the motion engine for a single sensor frame.
 *
 * deltaX / deltaY are cursor velocities in pixels per second.
 * The Windows side multiplies by elapsed time to get pixel offset.
 *
 * Using velocity rather than pixel deltas decouples the cursor movement
 * from the exact timing of packet delivery — late packets still
 * produce correct cursor travel distance.
 */
data class MotionOutput(
    val deltaX: Float,
    val deltaY: Float,
    val timestampMs: Long
)
