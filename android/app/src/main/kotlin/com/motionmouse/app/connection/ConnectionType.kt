package com.motionmouse.app.connection

/**
 * Represents the available transport mechanisms.
 *
 * AUTO means the ConnectionManager decides based on priority:
 *   Bluetooth → Wi-Fi
 *   (USB will be added in a future version as highest priority)
 *
 * These are persisted as strings in DataStore (by name),
 * so do not rename values without a migration.
 */
enum class ConnectionType {
    AUTO,
    BLUETOOTH,
    WIFI;

    fun displayName(): String = when (this) {
        AUTO -> "Automatic"
        BLUETOOTH -> "Bluetooth"
        WIFI -> "Wi-Fi"
    }
}
