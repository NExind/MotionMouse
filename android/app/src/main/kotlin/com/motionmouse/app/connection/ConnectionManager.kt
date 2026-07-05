package com.motionmouse.app.connection

import android.content.Context
import android.util.Log
import com.motionmouse.app.connection.bluetooth.BluetoothDiscovery
import com.motionmouse.app.connection.bluetooth.BluetoothTransport
import com.motionmouse.app.connection.wifi.UdpDiscovery
import com.motionmouse.app.connection.wifi.WifiTransport
import com.motionmouse.app.protocol.PacketBuilder
import com.motionmouse.app.protocol.PacketParser
import com.motionmouse.app.settings.MotionSettings
import com.motionmouse.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConnectionManager"
private const val PING_INTERVAL_MS = 2000L
private const val STATUS_INTERVAL_MS = 5000L
private const val RECONNECT_DELAY_MS = 2000L
private const val MAX_RECONNECT_ATTEMPTS = 5

@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiTransport: WifiTransport,
    private val bluetoothTransport: BluetoothTransport,
    private val udpDiscovery: UdpDiscovery,
    private val bluetoothDiscovery: BluetoothDiscovery,
    private val settingsRepository: SettingsRepository
) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private var activeTransport: Transport? = null
    private var connectedPcName: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var discoveryJob: Job? = null
    private var btDiscoveryJob: Job? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var statusJob: Job? = null
    private var reconnectJob: Job? = null

    private val pendingPings = mutableMapOf<Long, Long>()

    var batteryLevel: Int = 100
    var isLocked: Boolean = false

    private var reconnectAttempts = 0
    private var lastHost: DiscoveredHost? = null

    fun startDiscovery() {
        if (_connectionState.value is ConnectionState.Connected) return
        _connectionState.value = ConnectionState.Searching
        _discoveredHosts.value = emptyList()
        reconnectAttempts = 0

        discoveryJob = scope.launch {
            try {
                udpDiscovery.discoverHosts().collectLatest { host ->
                    addDiscoveredHost(host)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wi-Fi discovery error", e)
            }
        }

        btDiscoveryJob = scope.launch {
            try {
                bluetoothDiscovery.discoverHosts().collectLatest { host ->
                    addDiscoveredHost(host)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth discovery error", e)
            }
        }
        Log.d(TAG, "Discovery started")
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        btDiscoveryJob?.cancel()
        btDiscoveryJob = null
        udpDiscovery.stopDiscovery()
    }

    private fun addDiscoveredHost(host: DiscoveredHost) {
        val current = _discoveredHosts.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.address == host.address }
        if (existingIndex >= 0) {
            current[existingIndex] = host
        } else {
            current.add(host)
            Log.d(TAG, "Discovered: ${host.displayName} @ ${host.address}")
        }
        _discoveredHosts.value = current
    }

    fun connectTo(host: DiscoveredHost) {
        scope.launch { performConnect(host) }
    }

    private suspend fun performConnect(host: DiscoveredHost) {
        stopDiscovery()
        _connectionState.value = ConnectionState.Connecting(host.displayName)

        val transport = selectTransport(host)
        activeTransport = transport

        try {
            transport.connect(host)

            val deviceName = android.os.Build.MODEL
            transport.send(PacketBuilder.buildHello(deviceName), isCritical = true)

            // Wait for HELLO_ACK. The bounding timeout lives at the socket
            // level (SO_TIMEOUT in WifiTransport / RFCOMM read timeout in
            // BluetoothTransport), since coroutine-level withTimeout() cannot
            // actually interrupt a blocked Java InputStream.read() call.
            // If the socket times out, receive() throws TransportException,
            // which is caught by the outer try/catch in this function.
            val ack = transport.receive()

            if (ack == null) throw TransportException("No HELLO_ACK received — connection closed")

            val parsed = PacketParser.readNextPacket(ByteArrayInputStream(ack.bytes))
            if (parsed !is com.motionmouse.app.protocol.IncomingPacket.HelloAck) {
                throw TransportException("Expected HELLO_ACK, got $parsed")
            }

            connectedPcName = parsed.pcName
            lastHost = host
            reconnectAttempts = 0

            _connectionState.value = ConnectionState.Connected(
                pcName = parsed.pcName,
                connectionType = host.connectionType
            )

            Log.d(TAG, "Connected to ${parsed.pcName} via ${transport.name}")

            startReceiveLoop(transport)
            startPingLoop(transport)
            startStatusLoop(transport)

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            transport.close()
            activeTransport = null
            handleDisconnection(reason = e.message ?: "Connection failed")
        }
    }

    private fun selectTransport(host: DiscoveredHost): Transport {
        return when (host.connectionType) {
            ConnectionType.BLUETOOTH -> bluetoothTransport
            ConnectionType.WIFI -> wifiTransport
            else -> wifiTransport
        }
    }

    private fun startReceiveLoop(transport: Transport) {
        receiveJob = scope.launch {
            try {
                while (isActive && transport.isConnected) {
                    val data = transport.receive() ?: break

                    val packet = try {
                        PacketParser.readNextPacket(ByteArrayInputStream(data.bytes))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse packet", e)
                        continue
                    }

                    when (packet) {
                        is com.motionmouse.app.protocol.IncomingPacket.Pong -> {
                            handlePong(packet.originalSendTimestamp)
                        }
                        is com.motionmouse.app.protocol.IncomingPacket.SettingsSync -> {
                            settingsRepository.applyRemoteSync(
                                sensitivityX = packet.sensitivityX,
                                sensitivityY = packet.sensitivityY,
                                smoothingFactor = packet.smoothingFactor,
                                deadZone = packet.deadZone,
                                accelerationExponent = packet.accelerationExponent
                            )
                        }
                        is com.motionmouse.app.protocol.IncomingPacket.Disconnect -> {
                            Log.d(TAG, "Windows sent DISCONNECT")
                            break
                        }
                        else -> { /* Unknown or unhandled */ }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receive loop error", e)
            } finally {
                if (isActive) {
                    handleDisconnection("Connection lost")
                }
            }
        }
    }

    private fun startPingLoop(transport: Transport) {
        pingJob = scope.launch {
            while (isActive && transport.isConnected) {
                val sendTime = System.currentTimeMillis()
                pendingPings[sendTime] = sendTime
                transport.send(PacketBuilder.buildPing(sendTime), isCritical = false)
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun startStatusLoop(transport: Transport) {
        statusJob = scope.launch {
            while (isActive && transport.isConnected) {
                val connectionByte: Byte = when (transport.connectionType) {
                    ConnectionType.WIFI -> 0x01
                    ConnectionType.BLUETOOTH -> 0x02
                    else -> 0x01
                }
                transport.send(
                    PacketBuilder.buildStatus(batteryLevel, isLocked, connectionByte),
                    isCritical = false
                )
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    fun sendMotionPacket(packetBytes: ByteArray) {
        activeTransport?.send(packetBytes, isCritical = false)
    }

    fun sendButtonPacket(packetBytes: ByteArray) {
        activeTransport?.send(packetBytes, isCritical = true)
    }

    private fun handlePong(originalTimestamp: Long) {
        val sendTime = pendingPings.remove(originalTimestamp) ?: return
        val rtt = System.currentTimeMillis() - sendTime
        _latencyMs.value = rtt / 2
    }

    private fun handleDisconnection(reason: String) {
        Log.w(TAG, "Disconnected: $reason")
        cancelActiveJobs()
        activeTransport?.close()
        activeTransport = null

        val host = lastHost
        if (host != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            Log.d(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
            _connectionState.value = ConnectionState.Reconnecting(
                pcName = connectedPcName,
                attempt = reconnectAttempts
            )
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                performConnect(host)
            }
        } else {
            _connectionState.value = ConnectionState.Disconnected
            lastHost = null
            startDiscovery()
        }
    }

    fun disconnect() {
        scope.launch {
            activeTransport?.send(PacketBuilder.buildDisconnect(), isCritical = true)
            delay(100)
            cancelActiveJobs()
            activeTransport?.close()
            activeTransport = null
            lastHost = null
            reconnectAttempts = 0
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun cancelActiveJobs() {
        receiveJob?.cancel()
        pingJob?.cancel()
        statusJob?.cancel()
        reconnectJob?.cancel()
        receiveJob = null
        pingJob = null
        statusJob = null
        reconnectJob = null
    }

    fun shutdown() {
        disconnect()
        stopDiscovery()
        scope.cancel()
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Searching : ConnectionState()
    data class Connecting(val pcName: String) : ConnectionState()
    data class Connected(val pcName: String, val connectionType: ConnectionType) : ConnectionState()
    data class Reconnecting(val pcName: String, val attempt: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
