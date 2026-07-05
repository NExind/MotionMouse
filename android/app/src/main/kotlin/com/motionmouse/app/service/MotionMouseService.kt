package com.motionmouse.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.motionmouse.app.MotionMouseApplication
import com.motionmouse.app.R
import com.motionmouse.app.connection.ConnectionManager
import com.motionmouse.app.connection.ConnectionState
import com.motionmouse.app.motion.MotionEngine
import com.motionmouse.app.motion.MotionOutput
import com.motionmouse.app.motion.MotionPacketBuilder
import com.motionmouse.app.protocol.PacketBuilder
import com.motionmouse.app.sensor.SensorManagerWrapper
import com.motionmouse.app.sensor.SensorStatus
import com.motionmouse.app.settings.SettingsRepository
import com.motionmouse.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MotionMouseService"
private const val NOTIFICATION_ID = 1001

// Broadcast action for the notification's Disconnect button
private const val ACTION_DISCONNECT = "com.motionmouse.app.action.DISCONNECT"

/**
 * Foreground service that owns the motion pipeline and connection
 * while the app is running.
 *
 * Why a foreground service:
 *   Android aggressively kills background processes to save battery.
 *   A foreground service with a visible notification is exempt from
 *   this killing — it is the only reliable way to keep sensor
 *   registration and a network socket alive when the user locks
 *   their phone or switches apps.
 *
 *   This is correct and expected for a peripheral controller app.
 *   The user knows the app is running because of the notification.
 *
 * Why LifecycleService:
 *   LifecycleService gives us a Lifecycle owner, which means we can
 *   use lifecycleScope for coroutines that automatically cancel when
 *   the service stops. No manual coroutine cleanup needed.
 *
 * Lifecycle:
 *   Started by MainActivity.onCreate() via startForegroundService().
 *   Remains running until:
 *     - User taps Disconnect in the notification
 *     - User taps Disconnect in the app UI
 *     - MainActivity explicitly stops it on app close
 *
 * Data flow:
 *   SensorManagerWrapper emits MotionOutput via StateFlow
 *     → Service collects it in a coroutine
 *       → ConnectionManager.sendMotionPacket() sends it immediately
 *
 *   This path has zero coroutine context switches after the initial
 *   collection — the send() call goes directly to the socket.
 */
@AndroidEntryPoint
class MotionMouseService : LifecycleService() {

    @Inject lateinit var motionEngine: MotionEngine
    @Inject lateinit var sensorWrapper: SensorManagerWrapper
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var packetBuilder: MotionPacketBuilder
    @Inject lateinit var settingsRepository: SettingsRepository

    // Battery broadcast receiver — updates ConnectionManager.batteryLevel
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                connectionManager.batteryLevel = (level * 100 / scale)
            }
        }
    }

    // Track whether service has been started to avoid double-start
    private var isStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Handle disconnect action from notification button
        if (intent?.action == ACTION_DISCONNECT) {
            connectionManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        if (isStarted) return START_STICKY
        isStarted = true

        // Post foreground notification immediately — required within 5 seconds
        // of startForegroundService() or the system kills the service
        startForeground(NOTIFICATION_ID, buildNotification(isConnected = false, pcName = null))

        // Register battery receiver
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Start all subsystems
        startMotionPipeline()
        startSettingsObserver()
        startConnectionStateObserver()
        startMotionOutputForwarder()

        // Start discovery immediately when service starts
        connectionManager.startDiscovery()

        Log.d(TAG, "Service started")

        // START_STICKY: if the system kills the service, restart it
        // without the original intent. This ensures Motion Mouse
        // survives low-memory kills and resumes automatically.
        return START_STICKY
    }

    /**
     * Start the sensor pipeline.
     * Passes our MotionEngine to the sensor wrapper so it can
     * begin producing MotionOutput values.
     */
    private fun startMotionPipeline() {
        if (!sensorWrapper.hasRequiredSensors) {
            Log.e(TAG, "Device lacks required sensors — motion will not work")
            return
        }
        sensorWrapper.start(motionEngine)
        Log.d(TAG, "Motion pipeline started")
    }

    /**
     * Observe settings changes and push them to the motion engine
     * and sensor wrapper without restarting either.
     *
     * Uses distinctUntilChanged() so settings changes only propagate
     * when values actually change — not on every DataStore read.
     */
    private fun startSettingsObserver() {
        lifecycleScope.launch {
            settingsRepository.settingsFlow
                .distinctUntilChanged()
                .collectLatest { settings ->
                    motionEngine.updateSettings(settings)
                    sensorWrapper.updateSettings(settings)
                    Log.d(TAG, "Settings updated: sensitivityX=${settings.sensitivityX}, sensitivityY=${settings.sensitivityY}")
                }
        }
    }

    /**
     * Observe connection state and update the notification accordingly.
     *
     * The notification displays whether we're connected and to whom,
     * so the user always knows the state when looking at their notification shade.
     */
    private fun startConnectionStateObserver() {
        lifecycleScope.launch {
            connectionManager.connectionState
                .collectLatest { state ->
                    when (state) {
                        is ConnectionState.Connected -> {
                            updateNotification(isConnected = true, pcName = state.pcName)
                            Log.d(TAG, "Connected to ${state.pcName}")
                        }
                        is ConnectionState.Disconnected,
                        is ConnectionState.Searching -> {
                            updateNotification(isConnected = false, pcName = null)
                        }
                        is ConnectionState.Reconnecting -> {
                            updateNotification(
                                isConnected = false,
                                pcName = state.pcName,
                                isReconnecting = true
                            )
                        }
                        else -> { /* Connecting state — no notification change needed */ }
                    }
                }
        }
    }

    /**
     * The hot path — forward motion packets from the sensor pipeline
     * to the connection manager as fast as they arrive.
     *
     * collectLatest() drops intermediate values if the collector
     * is slower than the producer. For motion data this is correct —
     * we always want the most recent position, never a backlog.
     *
     * Only sends packets when actually connected — avoids useless
     * serialisation work when no one is listening.
     *
     * Calibration bias is applied here by adjusting the raw
     * MotionOutput using current settings before building the packet.
     */
    private fun startMotionOutputForwarder() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Combine motion output with connection state so we only
            // forward when connected — avoids building packets that go nowhere
            combine(
                sensorWrapper.motionOutput,
                connectionManager.connectionState
            ) { output, state ->
                Pair(output, state)
            }
            .sample(16L) // Throttle to ~60Hz to prevent TCP backlog
            .collectLatest { (output, state) ->
                if (output != null && state is ConnectionState.Connected) {
                    val packet = packetBuilder.build(output)
                    connectionManager.sendMotionPacket(packet)
                }
            }
        }
    }

    // --- Notification management ---

    /**
     * Build the foreground service notification.
     *
     * Shows connection status and a quick-action Disconnect button
     * so the user never has to open the app to stop Motion Mouse.
     *
     * Tapping the notification opens MainActivity.
     */
    private fun buildNotification(
        isConnected: Boolean,
        pcName: String?,
        isReconnecting: Boolean = false
    ): Notification {

        // Tapping the notification opens the main app screen
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect action button in the notification
        val disconnectIntent = Intent(this, MotionMouseService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            isReconnecting && pcName != null ->
                "Reconnecting to $pcName…"
            isConnected && pcName != null ->
                getString(R.string.notification_text_connected, pcName)
            else ->
                getString(R.string.notification_text_searching)
        }

        return NotificationCompat.Builder(this, MotionMouseApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPending)
            .setOngoing(true)          // Cannot be dismissed by swipe
            .setShowWhen(false)        // Don't show timestamp — irrelevant for a service
            .setSilent(true)           // No sound or vibration on update
            .addAction(
                R.drawable.ic_disconnect,
                "Disconnect",
                disconnectPending
            )
            .build()
    }

    /**
     * Update the existing notification in place.
     * Does not re-create or flash the notification.
     */
    private fun updateNotification(
        isConnected: Boolean,
        pcName: String?,
        isReconnecting: Boolean = false
    ) {
        val notification = buildNotification(isConnected, pcName, isReconnecting)
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // --- Lifecycle ---

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        // We don't use binding — the service communicates via shared
        // StateFlows in injected singletons. Returning null here is correct.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying")

        // Stop sensors first — no point processing motion with no connection
        sensorWrapper.stop()

        // Gracefully disconnect (sends DISCONNECT packet if connected)
        connectionManager.shutdown()

        // Unregister battery receiver
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Battery receiver not registered (safe to ignore)")
        }

        isStarted = false
        Log.d(TAG, "Service destroyed")
    }
}
