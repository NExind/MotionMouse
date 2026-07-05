package com.motionmouse.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.motionmouse.app.motion.SensorFusion
import com.motionmouse.app.motion.MotionEngine
import com.motionmouse.app.motion.MotionOutput
import com.motionmouse.app.settings.MotionSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var accelX: Float = 0f
    @Volatile private var accelY: Float = 0f
    @Volatile private var accelZ: Float = 9.81f

    private val sensorFusion = SensorFusion()
    private lateinit var motionEngine: MotionEngine

    private var isRunning = false

    private val _sensorStatus = MutableStateFlow(SensorStatus.UNKNOWN)
    val sensorStatus: StateFlow<SensorStatus> = _sensorStatus.asStateFlow()

    private val _motionOutput = MutableStateFlow<MotionOutput?>(null)
    val motionOutput: StateFlow<MotionOutput?> = _motionOutput.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

            val gyroX = event.values[0]
            val gyroY = event.values[1]
            val gyroZ = event.values[2]

            sensorFusion.update(
                gyroX = gyroX,
                gyroY = gyroY,
                gyroZ = gyroZ,
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
                timestampNanos = event.timestamp
            )

            val output = motionEngine.process(
                yawVelocity = sensorFusion.yawVelocity,
                pitchVelocity = sensorFusion.pitchVelocity,
                timestampMs = System.currentTimeMillis()
            )

            _motionOutput.value = output
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val accelListener = object : SensorEventListener {
        private val ACCEL_FILTER_ALPHA = 0.8f

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            accelX = ACCEL_FILTER_ALPHA * accelX + (1f - ACCEL_FILTER_ALPHA) * event.values[0]
            accelY = ACCEL_FILTER_ALPHA * accelY + (1f - ACCEL_FILTER_ALPHA) * event.values[1]
            accelZ = ACCEL_FILTER_ALPHA * accelZ + (1f - ACCEL_FILTER_ALPHA) * event.values[2]
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start(engine: MotionEngine) {
        if (isRunning) return
        motionEngine = engine

        val hasGyro = gyroscope != null
        val hasAccel = accelerometer != null

        _sensorStatus.value = when {
            !hasGyro -> SensorStatus.GYROSCOPE_UNAVAILABLE
            !hasAccel -> SensorStatus.ACCELEROMETER_UNAVAILABLE
            else -> SensorStatus.RUNNING
        }

        if (!hasGyro) return

        sensorManager.registerListener(
            gyroListener,
            gyroscope,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        if (hasAccel) {
            sensorManager.registerListener(
                accelListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        val gyroRate = gyroscope?.minDelay?.let { minDelay ->
            if (minDelay > 0) (1_000_000f / minDelay).coerceIn(50f, 400f)
            else 200f
        } ?: 100f
        sensorFusion.setSampleRate(gyroRate)

        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(gyroListener)
        sensorManager.unregisterListener(accelListener)
        sensorFusion.reset()
        _motionOutput.value = null
        isRunning = false
        _sensorStatus.value = SensorStatus.STOPPED
    }

    fun updateSettings(settings: MotionSettings) {
        if (::motionEngine.isInitialized) {
            motionEngine.updateSettings(settings)
        }
    }

    fun applyCalibration(yawBias: Float, pitchBias: Float) {
        sensorFusion.pitchMultiplier = SensorFusion.DEFAULT_PITCH_MULTIPLIER
        sensorFusion.yawMultiplier = SensorFusion.DEFAULT_YAW_MULTIPLIER
    }

    fun getCurrentYawVelocity(): Float? {
        return if (isRunning) sensorFusion.yawVelocity else null
    }

    fun getCurrentPitchVelocity(): Float? {
        return if (isRunning) sensorFusion.pitchVelocity else null
    }

    val hasRequiredSensors: Boolean get() = gyroscope != null
}

enum class SensorStatus {
    UNKNOWN,
    RUNNING,
    STOPPED,
    GYROSCOPE_UNAVAILABLE,
    ACCELEROMETER_UNAVAILABLE
}
