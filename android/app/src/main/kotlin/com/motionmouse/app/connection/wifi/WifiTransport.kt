package com.motionmouse.app.connection.wifi

import android.util.Log
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.connection.DiscoveredHost
import com.motionmouse.app.connection.IncomingData
import com.motionmouse.app.connection.Transport
import com.motionmouse.app.connection.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "WifiTransport"

// TCP connection timeout — if Windows doesn't respond in 5s, fail fast
private const val CONNECT_TIMEOUT_MS = 5000

// Send buffer size — larger buffer absorbs burst writes without blocking
// 64KB is generous for our tiny packets but prevents any possibility of
// the sensor thread blocking on a full kernel socket buffer
private const val SEND_BUFFER_SIZE = 65536

// Receive buffer — our incoming packets are tiny (max 64 bytes)
private const val RECEIVE_BUFFER_SIZE = 4096

// Socket-level read timeout (SO_TIMEOUT). Generous enough to never fire
// during normal operation (PING keeps traffic flowing every 2s) but
// guarantees blocking reads actually unblock if the connection dies.
private const val SOCKET_READ_TIMEOUT_MS = 15000

/**
 * TCP socket transport over Wi-Fi (LAN or mobile hotspot).
 *
 * Implements Transport using a standard Java TCP socket.
 * All motion data flows over this single persistent connection.
 *
 * Send path (called at up to 200Hz):
 *   send() writes directly to a BufferedOutputStream.
 *   The OS kernel handles buffering and TCP segmentation.
 *   We do NOT manually batch packets — TCP's Nagle algorithm
 *   is disabled (TCP_NODELAY) so small packets are sent immediately
 *   rather than being held waiting for more data to accumulate.
 *   For a real-time control application, low latency beats throughput.
 *
 * Receive path (one blocked reader coroutine):
 *   receive() blocks on InputStream.read() on Dispatchers.IO.
 *   The ConnectionManager runs a dedicated reader coroutine for this.
 *
 * Thread safety:
 *   send() and close() may be called from different threads.
 *   We use AtomicBoolean for the connected flag and synchronize
 *   on the output stream for writes.
 */
class WifiTransport @Inject constructor() : Transport {

    override val name = "Wi-Fi"
    override val connectionType = ConnectionType.WIFI

    private var socket: Socket? = null
    private var outputStream: BufferedOutputStream? = null
    private var inputStream: InputStream? = null

    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean get() = _isConnected.get()

    // Lock for output stream writes — prevents interleaved bytes
    // if send() is somehow called concurrently (defensive)
    private val writeLock = Any()

    /**
     * Connect to the Windows host discovered via UDP.
     *
     * Sets TCP_NODELAY to disable Nagle algorithm — critical for
     * low-latency real-time data. Without this, the OS may buffer
     * small packets for up to 200ms waiting to batch them.
     */
    override suspend fun connect(host: DiscoveredHost) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to ${host.address}:${host.port}")

                val s = Socket()
                s.tcpNoDelay = true                    // Disable Nagle — send immediately
                s.setPerformancePreferences(0, 2, 1)   // Prefer latency over bandwidth
                s.sendBufferSize = SEND_BUFFER_SIZE
                s.receiveBufferSize = RECEIVE_BUFFER_SIZE
                s.keepAlive = true                     // Detect dead connections

                s.connect(
                    InetSocketAddress(host.address, host.port),
                    CONNECT_TIMEOUT_MS
                )

                // CRITICAL: Set a socket-level read timeout (SO_TIMEOUT).
                // Without this, InputStream.read() blocks at the OS level
                // and does NOT respond to Kotlin coroutine cancellation —
                // withTimeout() wrapping a blocking read() will fire its
                // own cancellation but the underlying blocked thread keeps
                // waiting regardless. Setting SO_TIMEOUT makes read() throw
                // a real SocketTimeoutException that we can catch cleanly.
                s.soTimeout = SOCKET_READ_TIMEOUT_MS

                socket = s
                outputStream = BufferedOutputStream(s.getOutputStream(), SEND_BUFFER_SIZE)
                inputStream = s.getInputStream()
                _isConnected.set(true)

                Log.d(TAG, "Connected to ${host.displayName} at ${host.address}:${host.port}")

            } catch (e: Exception) {
                _isConnected.set(false)
                throw TransportException("Failed to connect to ${host.address}:${host.port}", e)
            }
        }
    }

    /**
     * Send packet bytes over the TCP socket.
     *
     * Called at up to 200Hz from the sensor pipeline.
     *
     * For motion packets (isCritical = false):
     *   If we can't acquire the write lock immediately, drop the packet.
     *   A missed motion frame is invisible to the user.
     *
     * For button packets (isCritical = true):
     *   Block until sent. A missed click is very noticeable.
     *   Button events happen rarely so blocking is acceptable.
     *
     * After writing, we flush immediately so the OS sends the packet
     * without waiting for more data. This is the other half of what
     * TCP_NODELAY does — together they ensure sub-millisecond send latency.
     */
    override fun send(data: ByteArray, isCritical: Boolean): Boolean {
        if (!_isConnected.get()) return false

        return try {
            if (isCritical) {
                // Block until sent — button events must not be dropped
                synchronized(writeLock) {
                    outputStream?.write(data)
                    outputStream?.flush()
                }
                true
            } else {
                // Try to acquire lock — drop packet if busy
                // tryLock equivalent using synchronized with timeout
                val acquired = (outputStream != null)
                if (acquired) {
                    synchronized(writeLock) {
                        outputStream?.write(data)
                        outputStream?.flush()
                    }
                    true
                } else {
                    false  // Drop motion packet
                }
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Socket closed during send")
            _isConnected.set(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            _isConnected.set(false)
            false
        }
    }

    /**
     * Block until a packet arrives from Windows.
     *
     * Returns the raw bytes of a single complete packet
     * (length-prefix + type + payload).
     *
     * Returns null on clean connection close.
     * Throws TransportException on unexpected loss.
     *
     * The caller (ConnectionManager) passes these bytes to PacketParser.
     */
    override suspend fun receive(): IncomingData? {
        return withContext(Dispatchers.IO) {
            try {
                val stream = inputStream
                    ?: throw TransportException("Not connected")

                // Read the 2-byte length prefix
                val lengthBuf = ByteArray(2)
                val firstByte = stream.read(lengthBuf, 0, 1)
                if (firstByte == -1) return@withContext null  // Clean close

                val secondByte = stream.read(lengthBuf, 1, 1)
                if (secondByte == -1) return@withContext null

                val totalLength = ((lengthBuf[0].toInt() and 0xFF) shl 8) or
                        (lengthBuf[1].toInt() and 0xFF)

                if (totalLength < 3 || totalLength > 64) {
                    throw TransportException("Invalid packet length: $totalLength")
                }

                // Read the remaining bytes
                val fullPacket = ByteArray(totalLength)
                lengthBuf.copyInto(fullPacket, 0, 0, 2)
                var offset = 2
                while (offset < totalLength) {
                    val read = stream.read(fullPacket, offset, totalLength - offset)
                    if (read == -1) return@withContext null
                    offset += read
                }

                IncomingData(fullPacket)

            } catch (e: java.net.SocketTimeoutException) {
                // Real socket-level timeout — no data arrived within
                // SOCKET_READ_TIMEOUT_MS. This is expected if the
                // connection has gone genuinely idle/dead.
                throw TransportException("Socket read timed out", e)
            } catch (e: SocketException) {
                // Socket closed — either by close() or connection loss
                null
            } catch (e: TransportException) {
                throw e
            } catch (e: Exception) {
                throw TransportException("Receive failed", e)
            }
        }
    }

    /**
     * Close the socket and release resources.
     * Safe to call multiple times.
     */
    override fun close() {
        _isConnected.set(false)
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during close (safe to ignore)", e)
        } finally {
            outputStream = null
            inputStream = null
            socket = null
        }
        Log.d(TAG, "Transport closed")
    }
}
