package com.motionmouse.app.connection.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.connection.DiscoveredHost
import com.motionmouse.app.connection.IncomingData
import com.motionmouse.app.connection.Transport
import com.motionmouse.app.connection.TransportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BluetoothTransport"

/**
 * Bluetooth RFCOMM transport using a custom service UUID.
 *
 * Why RFCOMM with a custom UUID rather than Bluetooth HID:
 *
 *   Bluetooth HID (Human Interface Device) profile would let the phone
 *   appear as a standard mouse without needing the Windows companion app.
 *   However, Android's HID support is heavily fragmented:
 *     - Requires the phone to act as a BT peripheral (not all devices support this)
 *     - HID descriptor must be registered at the OS level (requires root on many devices)
 *     - No way to send custom data alongside HID reports (no settings sync, no latency)
 *     - Cannot implement our motion engine on-device and just send deltas
 *
 *   RFCOMM gives us a raw bidirectional byte stream — exactly like a serial port.
 *   This lets us use our full protocol over Bluetooth exactly as we do over Wi-Fi TCP.
 *   The only difference between Wi-Fi and Bluetooth transports is the socket type.
 *
 * RFCOMM connection flow:
 *   1. Get BluetoothDevice by MAC address from DiscoveredHost
 *   2. Create RFCOMM socket using our service UUID
 *      (Windows must register the same UUID in its RFCOMM server)
 *   3. connect() — blocks until connected or throws
 *   4. Get input/output streams — same API as TCP sockets from here on
 *
 * Latency characteristics:
 *   Bluetooth RFCOMM adds ~5-15ms one-way latency on a clean connection.
 *   This is slightly higher than Wi-Fi on a good LAN (~1-3ms) but still
 *   well within the threshold for perceptible mouse lag (~50ms).
 *   For most users, Bluetooth will feel identical to Wi-Fi.
 *
 * Known limitation:
 *   BluetoothSocket does not support TCP_NODELAY — RFCOMM handles
 *   packet framing at the L2CAP layer, so small packets are always
 *   sent promptly. Nagle-equivalent buffering is not an issue here.
 */
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : Transport {

    override val name = "Bluetooth"
    override val connectionType = ConnectionType.BLUETOOTH

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean get() = _isConnected.get()

    // Lock for output stream — prevents interleaved bytes on concurrent writes
    private val writeLock = Any()

    // Service UUID — must match the Windows companion's RFCOMM server UUID exactly
    private val serviceUuid = UUID.fromString(BluetoothDiscovery.MOTION_MOUSE_UUID)

    /**
     * Connect to the Windows PC via Bluetooth RFCOMM.
     *
     * The host.address field contains the Bluetooth MAC address
     * (format: "AA:BB:CC:DD:EE:FF").
     *
     * We cancel any in-progress Bluetooth discovery before connecting —
     * active discovery interferes with RFCOMM connections and significantly
     * increases connection time.
     *
     * @throws TransportException if connection fails for any reason.
     */
    override suspend fun connect(host: DiscoveredHost) {
        withContext(Dispatchers.IO) {
            try {
                val adapter = bluetoothAdapter
                    ?: throw TransportException("Bluetooth not available")

                // Cancel discovery — it interferes with connection speed
                try {
                    @Suppress("MissingPermission")
                    if (adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                        Log.d(TAG, "Cancelled ongoing BT discovery before connect")
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not cancel discovery (permission)", e)
                }

                // Get BluetoothDevice from MAC address
                val device: BluetoothDevice = try {
                    adapter.getRemoteDevice(host.address)
                } catch (e: IllegalArgumentException) {
                    throw TransportException("Invalid Bluetooth address: ${host.address}")
                }

                Log.d(TAG, "Creating RFCOMM socket to ${host.displayName} (${host.address})")

                // Create RFCOMM socket using our service UUID.
                // The Windows companion registers an RFCOMM server with this same UUID.
                // The OS negotiates the actual RFCOMM channel automatically.
                val btSocket = try {
                    @Suppress("MissingPermission")
                    device.createRfcommSocketToServiceRecord(serviceUuid)
                } catch (e: IOException) {
                    throw TransportException("Failed to create RFCOMM socket", e)
                }

                // Attempt connection — this blocks until connected or fails
                try {
                    Log.d(TAG, "Connecting RFCOMM socket...")
                    @Suppress("MissingPermission")
                    btSocket.connect()
                } catch (e: IOException) {
                    btSocket.close()

                    // Fallback: try insecure RFCOMM if secure fails.
                    // Some Android-Windows pairings have handshake issues with
                    // secure sockets despite being properly paired.
                    Log.w(TAG, "Secure RFCOMM failed, trying insecure fallback", e)
                    val insecureSocket = try {
                        @Suppress("MissingPermission")
                        device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                    } catch (e2: IOException) {
                        throw TransportException("Failed to create insecure RFCOMM socket", e2)
                    }

                    try {
                        @Suppress("MissingPermission")
                        insecureSocket.connect()
                        socket = insecureSocket
                    } catch (e2: IOException) {
                        insecureSocket.close()
                        throw TransportException(
                            "RFCOMM connection failed (both secure and insecure)", e2
                        )
                    }
                }

                // If we reach here without throwing, connection succeeded
                if (socket == null) {
                    socket = btSocket
                }

                outputStream = socket!!.outputStream
                inputStream = socket!!.inputStream
                _isConnected.set(true)

                Log.d(TAG, "Bluetooth connected to ${host.displayName}")

            } catch (e: TransportException) {
                _isConnected.set(false)
                throw e
            } catch (e: Exception) {
                _isConnected.set(false)
                throw TransportException("Unexpected error during BT connect", e)
            }
        }
    }

    /**
     * Send packet bytes over the RFCOMM stream.
     *
     * Functionally identical to WifiTransport.send().
     * RFCOMM delivers data as a stream — same framing rules apply.
     *
     * Unlike TCP, RFCOMM does not have a configurable Nagle algorithm.
     * Small packets are always transmitted promptly by the BT stack.
     *
     * For critical packets (button events), we block and flush.
     * For motion packets, we write and flush but don't wait on a lock.
     */
    override fun send(data: ByteArray, isCritical: Boolean): Boolean {
        if (!_isConnected.get()) return false

        return try {
            synchronized(writeLock) {
                outputStream?.write(data)
                outputStream?.flush()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "BT send failed — socket likely closed")
            _isConnected.set(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected BT send error", e)
            _isConnected.set(false)
            false
        }
    }

    /**
     * Block until a packet arrives from Windows over Bluetooth.
     *
     * Identical in structure to WifiTransport.receive().
     * The RFCOMM input stream behaves like a TCP stream —
     * length-prefix framing is required for the same reasons.
     *
     * Returns null on clean socket close.
     * Throws TransportException on unexpected loss.
     */
    override suspend fun receive(): IncomingData? {
        return withContext(Dispatchers.IO) {
            try {
                val stream = inputStream
                    ?: throw TransportException("Not connected")

                // Read 2-byte length prefix
                val lengthBuf = ByteArray(2)
                val first = stream.read(lengthBuf, 0, 1)
                if (first == -1) return@withContext null

                val second = stream.read(lengthBuf, 1, 1)
                if (second == -1) return@withContext null

                val totalLength = ((lengthBuf[0].toInt() and 0xFF) shl 8) or
                        (lengthBuf[1].toInt() and 0xFF)

                if (totalLength < 3 || totalLength > 64) {
                    throw TransportException("Invalid BT packet length: $totalLength")
                }

                // Read remaining bytes
                val fullPacket = ByteArray(totalLength)
                lengthBuf.copyInto(fullPacket, 0, 0, 2)
                var offset = 2
                while (offset < totalLength) {
                    val read = stream.read(fullPacket, offset, totalLength - offset)
                    if (read == -1) return@withContext null
                    offset += read
                }

                IncomingData(fullPacket)

            } catch (e: IOException) {
                // Socket closed — either intentionally or connection lost
                if (_isConnected.get()) {
                    Log.w(TAG, "BT connection lost during receive")
                    _isConnected.set(false)
                    throw TransportException("Bluetooth connection lost", e)
                }
                null
            }
        }
    }

    /**
     * Close the RFCOMM socket and release all resources.
     * Safe to call from any thread, safe to call multiple times.
     */
    override fun close() {
        _isConnected.set(false)
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during BT close (safe to ignore)", e)
        } finally {
            outputStream = null
            inputStream = null
            socket = null
        }
        Log.d(TAG, "Bluetooth transport closed")
    }
}
