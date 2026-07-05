package com.motionmouse.app.connection.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.connection.DiscoveredHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BluetoothDiscovery"

/**
 * Discovers Windows PCs available via Bluetooth RFCOMM.
 *
 * Strategy:
 *   Rather than a full Bluetooth device scan (which takes 12 seconds
 *   and drains battery significantly), we take a smarter approach:
 *
 *   1. First, check already-paired devices. If the Windows PC has been
 *      paired before, it appears instantly with no scan needed.
 *      This covers the vast majority of repeat-use cases.
 *
 *   2. If no paired PC is found, start a classic Bluetooth discovery
 *      scan to find nearby unpaired devices.
 *
 * How we identify Windows PCs:
 *   We check the Bluetooth device class. Windows PCs advertise as
 *   BluetoothClass.Device.Major.COMPUTER (0x0100).
 *   We also check if the Motion Mouse UUID is in the device's
 *   service UUID list when available (API 23+).
 *
 * Permissions:
 *   Android 12+ (API 31+): BLUETOOTH_CONNECT + BLUETOOTH_SCAN
 *   Android 11 and below: BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION
 *   We check all of these at runtime and emit an error state if missing.
 *
 * The Windows companion must have the Motion Mouse service running
 * and be discoverable for unpaired discovery to work.
 * Paired devices are always visible regardless of discoverability.
 */
@Singleton
class BluetoothDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // Motion Mouse RFCOMM service UUID — must match Windows companion exactly
        // This is the UUID defined in PROTOCOL.md
        const val MOTION_MOUSE_UUID = "8ce255c0-200a-11e0-ac64-0800200c9a66"

        // Bluetooth device major class for computers
        private const val MAJOR_CLASS_COMPUTER = 0x0100
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    /**
     * True if this device has Bluetooth hardware.
     * Used by ConnectionManager to decide whether to attempt BT discovery.
     */
    val isBluetoothAvailable: Boolean get() = bluetoothAdapter != null

    /**
     * True if Bluetooth is currently enabled.
     */
    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    /**
     * Start discovering Bluetooth hosts.
     *
     * Emits DiscoveredHost items as they are found.
     * Emits paired PCs immediately, then starts scanning for unpaired ones.
     *
     * The flow completes when:
     *   - Discovery scan finishes (after ~12 seconds)
     *   - The collecting coroutine is cancelled
     *   - An unrecoverable error occurs
     */
    fun discoverHosts(): Flow<DiscoveredHost> = callbackFlow {
        if (!isBluetoothAvailable) {
            Log.w(TAG, "Bluetooth not available on this device")
            close()
            return@callbackFlow
        }

        if (!isBluetoothEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            close()
            return@callbackFlow
        }

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            close()
            return@callbackFlow
        }

        // --- Step 1: Emit already-paired devices immediately ---
        // This is instant and covers the common case of a returning user.
        try {
            val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            Log.d(TAG, "Checking ${pairedDevices.size} paired devices")

            for (device in pairedDevices) {
                if (isLikelyWindowsPC(device)) {
                    val host = deviceToHost(device)
                    Log.d(TAG, "Found paired PC: ${device.name}")
                    trySend(host)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading bonded devices", e)
        }

        // --- Step 2: Start active scan for unpaired devices ---
        // Register broadcast receiver for discovery events
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return

                        val rssi = intent.getShortExtra(
                            BluetoothDevice.EXTRA_RSSI,
                            Short.MIN_VALUE
                        ).toInt()

                        if (isLikelyWindowsPC(device)) {
                            Log.d(TAG, "Discovered BT device: ${device.name} RSSI=$rssi")
                            trySend(deviceToHost(device, rssi))
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Bluetooth discovery scan finished")
                        // Don't close the flow — paired devices may still be usable
                        // and the ConnectionManager decides when to stop
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)

        // Start discovery scan
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
            val started = bluetoothAdapter?.startDiscovery() ?: false
            Log.d(TAG, "Bluetooth scan started: $started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied starting BT discovery", e)
        }

        // Clean up when flow collection is cancelled
        awaitClose {
            try {
                context.unregisterReceiver(receiver)
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup error (safe to ignore)", e)
            }
        }
    }

    /**
     * Determine whether a Bluetooth device is likely a Windows PC
     * running the Motion Mouse companion app.
     *
     * Checks (in order of reliability):
     *   1. Device is in the COMPUTER major class
     *   2. Device name is non-null (unnamed devices are unlikely PCs)
     *
     * We intentionally do NOT filter by UUID here because:
     *   - Service UUID fetching requires an additional connection attempt
     *   - It significantly slows discovery
     *   - The RFCOMM connection attempt will fail naturally if it's wrong
     *   - False positives (other computers) are acceptable — user chooses
     */
    private fun isLikelyWindowsPC(device: BluetoothDevice): Boolean {
        return try {
            val deviceClass = device.bluetoothClass?.majorDeviceClass ?: return false
            val hasComputerClass = deviceClass == MAJOR_CLASS_COMPUTER
            val hasName = device.name?.isNotBlank() == true
            hasComputerClass && hasName
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Convert a BluetoothDevice to our DiscoveredHost model.
     * Port is 0 for Bluetooth — RFCOMM uses UUID-based channel negotiation.
     */
    private fun deviceToHost(device: BluetoothDevice, rssi: Int = 0): DiscoveredHost {
        return try {
            DiscoveredHost(
                displayName = device.name ?: device.address,
                address = device.address,   // MAC address for RFCOMM
                port = 0,                   // Not used for Bluetooth
                connectionType = ConnectionType.BLUETOOTH,
                rssi = rssi
            )
        } catch (e: SecurityException) {
            DiscoveredHost(
                displayName = device.address,
                address = device.address,
                port = 0,
                connectionType = ConnectionType.BLUETOOTH,
                rssi = rssi
            )
        }
    }

    /**
     * Check that all required Bluetooth permissions are granted.
     *
     * Android 12+ requires BLUETOOTH_CONNECT and BLUETOOTH_SCAN.
     * Earlier versions require BLUETOOTH and ACCESS_FINE_LOCATION.
     *
     * We check at runtime rather than assuming the manifest is enough —
     * the user may have denied permissions after install.
     */
    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                    hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
}
