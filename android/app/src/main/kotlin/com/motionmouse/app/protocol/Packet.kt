package com.motionmouse.app.protocol

/**
 * Defines all packet type constants and their payload structures
 * as documented in PROTOCOL.md.
 *
 * These byte constants are the wire format — they must never change
 * between versions without a protocol version bump.
 *
 * Sealed class hierarchy gives us exhaustive when() expressions
 * in the parser — the compiler will warn us if we forget a packet type.
 */
object PacketType {
    const val HELLO: Byte          = 0x01
    const val HELLO_ACK: Byte      = 0x02
    const val MOTION: Byte         = 0x03
    const val BUTTON: Byte         = 0x04
    const val PING: Byte           = 0x05
    const val PONG: Byte           = 0x06
    const val SETTINGS_SYNC: Byte  = 0x07
    const val STATUS: Byte         = 0x08
    const val DISCONNECT: Byte     = 0x09
}

object ButtonId {
    const val LEFT: Byte  = 0x01
    const val RIGHT: Byte = 0x02
}

object ButtonAction {
    const val PRESS: Byte   = 0x01
    const val RELEASE: Byte = 0x02
}

object ConnectionTypeByte {
    const val WIFI:      Byte = 0x01
    const val BLUETOOTH: Byte = 0x02
    const val USB:       Byte = 0x03  // Reserved for future use
}

/**
 * Sealed hierarchy of all parsed incoming packets.
 *
 * The parser produces these — consumers use when() to handle each type.
 * Having a sealed class means adding a new packet type forces every
 * when() site to handle it or explicitly ignore it via an else branch.
 */
sealed class IncomingPacket {

    /**
     * Windows acknowledged our HELLO.
     * Contains the PC's display name for the UI.
     */
    data class HelloAck(
        val protocolVersion: Int,
        val pcName: String
    ) : IncomingPacket()

    /**
     * Windows is pushing new settings to the phone.
     * SettingsRepository.applyRemoteSync() handles this.
     */
    data class SettingsSync(
        val sensitivityX: Float,
        val sensitivityY: Float,
        val smoothingFactor: Float,
        val deadZone: Float,
        val accelerationExponent: Float
    ) : IncomingPacket()

    /**
     * Windows echoed our PING — used to calculate round-trip latency.
     * Latency = (System.currentTimeMillis() - originalSendTimestamp) / 2
     */
    data class Pong(
        val originalSendTimestamp: Long
    ) : IncomingPacket()

    /**
     * Windows is disconnecting cleanly.
     * The connection manager should close the socket and update state.
     */
    object Disconnect : IncomingPacket()

    /**
     * A packet arrived with an unrecognised type byte.
     * Log and ignore — forward compatibility for future packet types.
     */
    data class Unknown(val typeId: Byte) : IncomingPacket()
}
