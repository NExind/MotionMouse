package com.motionmouse.app.connection

/**
 * Transport is the abstraction boundary between the connection manager
 * and the physical communication mechanism.
 *
 * Every transport (Wi-Fi TCP, Bluetooth RFCOMM, future USB) implements
 * this interface identically. The ConnectionManager never knows or cares
 * which transport is active — it only calls send() and close().
 *
 * This is the key architectural decision that keeps the motion engine
 * and protocol layer completely decoupled from networking specifics.
 *
 * Threading contract:
 *   - connect() blocks until connected or throws
 *   - send() is called from the sensor thread at up to 200Hz
 *     and must not block for more than ~1ms
 *   - close() may be called from any thread
 *   - The transport must be safe for concurrent send() + close()
 */
interface Transport {

    /**
     * Human-readable name for UI display and logging.
     * e.g. "Wi-Fi", "Bluetooth"
     */
    val name: String

    /**
     * The connection type enum value for this transport.
     * Used to populate STATUS packets.
     */
    val connectionType: ConnectionType

    /**
     * Attempt to connect to the given discovered host.
     *
     * Blocks until connected or throws TransportException.
     * Called from a background coroutine — blocking is fine here.
     *
     * @param host DiscoveredHost containing address and port info.
     * @throws TransportException if connection fails.
     */
    suspend fun connect(host: DiscoveredHost)

    /**
     * Send a pre-built packet byte array.
     *
     * Called at up to 200Hz from the sensor pipeline.
     * Must not block — if the socket buffer is full, drop the packet
     * rather than stalling the sensor thread.
     *
     * Motion packets are expendable — a dropped frame is invisible.
     * Button packets must not be dropped — implementations must
     * handle them with a retry or queue if needed.
     *
     * @param data Raw packet bytes including length prefix and type byte.
     * @param isCritical If true (button events), do not drop under load.
     * @return true if sent successfully, false if dropped.
     */
    fun send(data: ByteArray, isCritical: Boolean = false): Boolean

    /**
     * Read the next incoming packet's raw bytes.
     *
     * Blocks until data is available.
     * Returns null when the connection is closed cleanly.
     * Called from a dedicated reader coroutine.
     *
     * @throws TransportException on unexpected connection loss.
     */
    suspend fun receive(): IncomingData?

    /**
     * Close the connection and release all resources.
     * Safe to call multiple times — subsequent calls are no-ops.
     * Safe to call from any thread.
     */
    fun close()

    /**
     * Whether the transport is currently connected.
     */
    val isConnected: Boolean
}

/**
 * A packet received from the Windows application.
 * Wraps the raw bytes — PacketParser handles the actual parsing.
 */
data class IncomingData(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingData) return false
        return bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Represents a discovered Windows host ready to connect to.
 *
 * Created by discovery mechanisms (UDP broadcast, Bluetooth scan)
 * and passed to Transport.connect().
 */
data class DiscoveredHost(
    val displayName: String,        // PC name shown in UI
    val address: String,            // IP address or Bluetooth MAC
    val port: Int,                  // TCP port (Wi-Fi) or 0 (Bluetooth)
    val connectionType: ConnectionType,
    val rssi: Int = 0               // Signal strength (Bluetooth only, for sorting)
)

/**
 * Thrown when a transport operation fails.
 * The ConnectionManager catches this and updates connection state.
 */
class TransportException(message: String, cause: Throwable? = null)
    : Exception(message, cause)
