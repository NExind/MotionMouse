package com.motionmouse.app.protocol

import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PacketParser"

/**
 * Parses incoming binary packets from the Windows application.
 *
 * Reads from a blocking InputStream (TCP socket stream).
 * Must be called from a dedicated IO thread — it blocks on read().
 *
 * Framing strategy:
 *   1. Read exactly 2 bytes → total packet length
 *   2. Read exactly (length - 2) remaining bytes
 *   3. Parse type byte and route to appropriate handler
 *   4. Return sealed IncomingPacket
 *
 * This length-prefix framing ensures we always read exactly one
 * complete packet regardless of how TCP segments the stream.
 * TCP is a stream protocol — without framing, a single read()
 * might return half a packet or two packets concatenated.
 *
 * Error handling:
 *   - EOFException → connection closed cleanly → return null
 *   - IOException  → connection lost → propagate to caller
 *   - Malformed packet → log and return Unknown (don't crash)
 *   - Unknown type → return Unknown (forward compatibility)
 */
object PacketParser {

    private const val LENGTH_FIELD_SIZE = 2
    private const val MAX_PACKET_SIZE = 64  // Protocol enforced maximum

    /**
     * Read and parse the next packet from the stream.
     *
     * Blocks until a complete packet is available.
     *
     * @return Parsed IncomingPacket, or null if the connection was
     *         closed cleanly (EOF).
     * @throws java.io.IOException if the connection was lost unexpectedly.
     */
    fun readNextPacket(inputStream: InputStream): IncomingPacket? {
        // Step 1: Read 2-byte length prefix
        val lengthBytes = ByteArray(LENGTH_FIELD_SIZE)
        try {
            readFully(inputStream, lengthBytes)
        } catch (e: EOFException) {
            // Clean connection close — not an error
            return null
        }

        val totalLength = ByteBuffer.wrap(lengthBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .short
            .toInt() and 0xFFFF  // Treat as unsigned

        // Validate length before allocating
        if (totalLength < LENGTH_FIELD_SIZE + 1 || totalLength > MAX_PACKET_SIZE) {
            Log.w(TAG, "Invalid packet length: $totalLength — dropping connection")
            return null  // Caller should close socket
        }

        // Step 2: Read remaining bytes (everything after the length prefix)
        val remainingSize = totalLength - LENGTH_FIELD_SIZE
        val remainingBytes = ByteArray(remainingSize)
        readFully(inputStream, remainingBytes)

        // Step 3: Parse type and payload
        val buffer = ByteBuffer.wrap(remainingBytes).order(ByteOrder.BIG_ENDIAN)
        val typeId = buffer.get()

        return when (typeId) {
            PacketType.HELLO_ACK    -> parseHelloAck(buffer)
            PacketType.SETTINGS_SYNC -> parseSettingsSync(buffer)
            PacketType.PONG         -> parsePong(buffer)
            PacketType.DISCONNECT   -> IncomingPacket.Disconnect
            else -> {
                Log.d(TAG, "Unknown packet type: 0x${typeId.toString(16)} — ignoring")
                IncomingPacket.Unknown(typeId)
            }
        }
    }

    // --- Individual packet parsers ---

    /**
     * HELLO_ACK (0x02)
     * Payload: [1] version, [1] nameLen, [N] pcName
     */
    private fun parseHelloAck(buffer: ByteBuffer): IncomingPacket {
        return try {
            val version = buffer.get().toInt() and 0xFF
            val nameLen = buffer.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val pcName = String(nameBytes, Charsets.UTF_8)

            if (version == 0xFF) {
                // Windows rejected our protocol version
                Log.e(TAG, "Protocol version mismatch — server rejected connection")
                IncomingPacket.Unknown(PacketType.HELLO_ACK)
            } else {
                IncomingPacket.HelloAck(version, pcName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HELLO_ACK", e)
            IncomingPacket.Unknown(PacketType.HELLO_ACK)
        }
    }

    /**
     * SETTINGS_SYNC
     * Payload: [4] sensitivityX, [4] sensitivityY, [4] smoothing, [4] deadZone, [4] accelExponent
     */
    private fun parseSettingsSync(buffer: ByteBuffer): IncomingPacket {
        return try {
            IncomingPacket.SettingsSync(
                sensitivityX = buffer.float,
                sensitivityY = buffer.float,
                smoothingFactor = buffer.float,
                deadZone = buffer.float,
                accelerationExponent = buffer.float
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SETTINGS_SYNC", e)
            IncomingPacket.Unknown(PacketType.SETTINGS_SYNC)
        }
    }

    /**
     * PONG (0x06)
     * Payload: [8] original send timestamp
     */
    private fun parsePong(buffer: ByteBuffer): IncomingPacket {
        return try {
            IncomingPacket.Pong(originalSendTimestamp = buffer.long)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PONG", e)
            IncomingPacket.Unknown(PacketType.PONG)
        }
    }

    // --- Stream reading utility ---

    /**
     * Read exactly [length] bytes from [stream] into [buffer].
     *
     * InputStream.read() is not guaranteed to return all requested bytes
     * in a single call — it may return fewer. This is especially common
     * with TCP sockets. readFully() loops until we have exactly what we need.
     *
     * @throws EOFException if the stream ends before we read enough bytes.
     * @throws IOException  if the stream throws during read.
     */
    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = stream.read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) throw EOFException("Stream ended after $offset bytes")
            offset += bytesRead
        }
    }
}
