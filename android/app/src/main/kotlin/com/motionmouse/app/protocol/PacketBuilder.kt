package com.motionmouse.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds outgoing binary packets as ByteArrays.
 *
 * All packets follow the format defined in PROTOCOL.md:
 *   [ 2 bytes: total length (uint16, big-endian) ]
 *   [ 1 byte:  packet type ]
 *   [ N bytes: payload ]
 *
 * Design decisions:
 *
 * - ByteBuffer with BIG_ENDIAN order matches the protocol spec.
 * - We pre-calculate exact buffer sizes to avoid resizing allocations.
 * - All builders are pure functions (no state) — safe to call from any thread.
 * - The MOTION builder is called at 100-200Hz so it is kept tight:
 *   no logging, no validation, no allocation beyond the ByteArray itself.
 *
 * All multi-byte values are big-endian per the protocol spec.
 */
object PacketBuilder {

    // Protocol framing constants
    private const val LENGTH_FIELD_SIZE = 2   // uint16 length prefix
    private const val TYPE_FIELD_SIZE = 1     // 1 byte type id
    private const val HEADER_SIZE = LENGTH_FIELD_SIZE + TYPE_FIELD_SIZE

    /**
     * HELLO packet (0x01) — Android → Windows
     *
     * Sent immediately after TCP connection established.
     * Windows will reject motion packets until it receives this.
     *
     * Payload:
     *   [1] protocol version = 0x01
     *   [1] device name length
     *   [N] device name (UTF-8, max 32 chars)
     */
    fun buildHello(deviceName: String): ByteArray {
        val nameBytes = deviceName
            .take(32)           // Enforce max 32 chars
            .toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 1 + nameBytes.size   // version + nameLen + name
        val totalSize = HEADER_SIZE + payloadSize

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())          // Total length
            .put(PacketType.HELLO)                  // Type
            .put(0x02.toByte())                     // Protocol version
            .put(nameBytes.size.toByte())           // Name length
            .put(nameBytes)                         // Name
            .array()
    }

    /**
     * MOTION packet (0x03) — Android → Windows
     *
     * The highest-frequency packet — sent at sensor rate (100-200Hz).
     * Every byte counts here.
     *
     * Payload:
     *   [4] delta_x (float, IEEE 754)
     *   [4] delta_y (float, IEEE 754)
     *   [8] timestamp (int64, ms since epoch)
     *
     * Total: 3 (header) + 16 (payload) = 19 bytes per motion update.
     */
    fun buildMotionPacket(
        deltaX: Float,
        deltaY: Float,
        timestampMs: Long
    ): ByteArray {
        val payloadSize = 4 + 4 + 8   // float + float + long
        val totalSize = HEADER_SIZE + payloadSize  // = 19 bytes

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())
            .put(PacketType.MOTION)
            .putFloat(deltaX)
            .putFloat(deltaY)
            .putLong(timestampMs)
            .array()
    }

    /**
     * BUTTON packet (0x04) — Android → Windows
     *
     * Sent on button press and release.
     * Both press and release must be sent — Windows uses SendInput()
     * which requires explicit down and up events.
     *
     * Payload:
     *   [1] button id   (0x01 = left, 0x02 = right)
     *   [1] action      (0x01 = press, 0x02 = release)
     */
    fun buildButtonPacket(buttonId: Byte, action: Byte): ByteArray {
        val payloadSize = 1 + 1
        val totalSize = HEADER_SIZE + payloadSize

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())
            .put(PacketType.BUTTON)
            .put(buttonId)
            .put(action)
            .array()
    }

    /**
     * Convenience wrappers for button events.
     * These are what the UI calls directly.
     */
    fun buildLeftPress()    = buildButtonPacket(ButtonId.LEFT,  ButtonAction.PRESS)
    fun buildLeftRelease()  = buildButtonPacket(ButtonId.LEFT,  ButtonAction.RELEASE)
    fun buildRightPress()   = buildButtonPacket(ButtonId.RIGHT, ButtonAction.PRESS)
    fun buildRightRelease() = buildButtonPacket(ButtonId.RIGHT, ButtonAction.RELEASE)

    /**
     * PING packet (0x05) — Android → Windows
     *
     * Sent every 2 seconds to measure round-trip latency.
     * Windows echoes the timestamp back in a PONG packet.
     *
     * Payload:
     *   [8] send_timestamp (int64, ms since epoch)
     */
    fun buildPing(sendTimestampMs: Long): ByteArray {
        val payloadSize = 8
        val totalSize = HEADER_SIZE + payloadSize

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())
            .put(PacketType.PING)
            .putLong(sendTimestampMs)
            .array()
    }

    /**
     * STATUS packet (0x08) — Android → Windows
     *
     * Sent every 5 seconds or on state change.
     * Keeps the Windows UI showing accurate battery and lock status.
     *
     * Payload:
     *   [1] battery level (0-100)
     *   [1] is_locked (0x00 = active, 0x01 = locked)
     *   [1] connection_type byte
     */
    fun buildStatus(
        batteryLevel: Int,
        isLocked: Boolean,
        connectionTypeByte: Byte
    ): ByteArray {
        val payloadSize = 1 + 1 + 1
        val totalSize = HEADER_SIZE + payloadSize

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())
            .put(PacketType.STATUS)
            .put(batteryLevel.coerceIn(0, 100).toByte())
            .put(if (isLocked) 0x01.toByte() else 0x00.toByte())
            .put(connectionTypeByte)
            .array()
    }

    /**
     * DISCONNECT packet (0x09) — Either direction
     *
     * Sent before intentionally closing the socket.
     * No payload — just the header.
     */
    fun buildDisconnect(): ByteArray {
        val totalSize = HEADER_SIZE  // Header only, no payload

        return ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(totalSize.toShort())
            .put(PacketType.DISCONNECT)
            .array()
    }
}
