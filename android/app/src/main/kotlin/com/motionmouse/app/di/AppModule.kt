package com.motionmouse.app.di

import android.content.Context
import com.motionmouse.app.connection.bluetooth.BluetoothDiscovery
import com.motionmouse.app.connection.bluetooth.BluetoothTransport
import com.motionmouse.app.connection.wifi.UdpDiscovery
import com.motionmouse.app.connection.wifi.WifiTransport
import com.motionmouse.app.connection.ConnectionManager
import com.motionmouse.app.motion.MotionEngine
import com.motionmouse.app.motion.MotionPacketBuilder
import com.motionmouse.app.sensor.SensorManagerWrapper
import com.motionmouse.app.settings.MotionSettings
import com.motionmouse.app.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 *
 * Provides all application-scoped singletons.
 *
 * Architecture note on scoping:
 *   Everything here is @Singleton — one instance for the app lifetime.
 *   This is correct because:
 *     - SensorManagerWrapper holds sensor registrations that must persist
 *     - ConnectionManager holds the active socket
 *     - SettingsRepository holds the DataStore reference
 *     - MotionEngine holds smoothing state that must not reset on recomposition
 *
 *   The foreground service and UI both inject the same instances,
 *   which is how sensor data flows from the service to the UI
 *   via shared StateFlows.
 *
 * Classes annotated with @Inject constructor() and @Singleton
 * (BluetoothTransport, WifiTransport, UdpDiscovery, BluetoothDiscovery,
 * SettingsRepository, SensorManagerWrapper, ConnectionManager)
 * are provided automatically by Hilt — no explicit @Provides needed.
 *
 * We only need explicit @Provides for:
 *   - MotionEngine: requires a MotionSettings parameter at construction
 *   - MotionPacketBuilder: simple object, explicit for clarity
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provide the initial MotionEngine instance with default settings.
     *
     * The engine's settings are updated at runtime via
     * MotionEngine.updateSettings() when the SettingsRepository
     * emits new values — it does not need to be recreated.
     *
     * We use default settings here because the service will
     * immediately collect the settings flow and call updateSettings()
     * before the first sensor event arrives.
     */
    @Provides
    @Singleton
    fun provideMotionEngine(): MotionEngine {
        return MotionEngine(settings = MotionSettings())
    }

    /**
     * Provide the MotionPacketBuilder.
     * Stateless — a single instance is fine.
     */
    @Provides
    @Singleton
    fun provideMotionPacketBuilder(): MotionPacketBuilder {
        return MotionPacketBuilder()
    }
}
