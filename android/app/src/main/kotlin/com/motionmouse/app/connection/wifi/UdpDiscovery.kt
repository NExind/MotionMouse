package com.motionmouse.app.connection.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.connection.DiscoveredHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import org.json.JSONException
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UdpDiscovery"
private const val DISCOVERY_PORT = 41234
private const val DATA_PORT = 41235
private const val SOCKET_TIMEOUT_MS = 3000
private const val MAX_DATAGRAM_SIZE = 512

@Singleton
class UdpDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Single multicast lock instance — acquired once, released once
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoverySocket: DatagramSocket? = null

    fun discoverHosts(): Flow<DiscoveredHost> = callbackFlow {
        // Acquire multicast lock — guarded so we never double-acquire
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("MotionMouseDiscovery").also {
                it.setReferenceCounted(false) // Non-reference-counted: single acquire/release
                it.acquire()
            }
        }

        // Close any leftover socket from a previous session (fixes EADDRINUSE)
        discoverySocket?.close()
        discoverySocket = null

        val socket = try {
            DatagramSocket(DISCOVERY_PORT).also {
                it.soTimeout = SOCKET_TIMEOUT_MS
                it.broadcast = true
                discoverySocket = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP socket on port $DISCOVERY_PORT", e)
            releaseMulticastLock()
            close()
            return@callbackFlow
        }

        Log.d(TAG, "UDP discovery started on port $DISCOVERY_PORT")

        try {
            val buffer = ByteArray(MAX_DATAGRAM_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket.receive(packet)
                    val json = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val host = parseAnnouncement(json, packet.address)
                    if (host != null) {
                        sendReply(socket, packet.address, getDeviceName())
                        trySend(host)
                    }
                } catch (e: SocketTimeoutException) {
                    continue // Normal — no broadcast in this window
                }
            }
        } catch (e: Exception) {
            if (isActive) Log.e(TAG, "Discovery socket error", e)
        } finally {
            socket.close()
            discoverySocket = null
            releaseMulticastLock()
            Log.d(TAG, "UDP discovery stopped")
        }

        awaitClose { stopDiscovery() }
    }.flowOn(Dispatchers.IO)

    private fun parseAnnouncement(json: String, senderAddress: InetAddress): DiscoveredHost? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("type") != "MOTION_MOUSE_ANNOUNCE") return null
            if (obj.optInt("version", -1) < 1) return null

            val pcName  = obj.optString("pc_name", "Unknown PC").ifBlank { "Unknown PC" }
            val tcpPort = obj.optInt("tcp_port", DATA_PORT)

            DiscoveredHost(
                displayName    = pcName,
                address        = senderAddress.hostAddress ?: return null,
                port           = tcpPort,
                connectionType = ConnectionType.WIFI
            )
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed UDP announcement")
            null
        }
    }

    private fun sendReply(socket: DatagramSocket, destination: InetAddress, deviceName: String) {
        try {
            val json  = """{"type":"MOTION_MOUSE_REPLY","version":1,"device_name":"$deviceName"}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, destination, DISCOVERY_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send reply", e)
        }
    }

    fun stopDiscovery() {
        discoverySocket?.close()
        discoverySocket = null
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock", e)
        } finally {
            multicastLock = null
        }
    }

    private fun getDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model        = android.os.Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }
}
