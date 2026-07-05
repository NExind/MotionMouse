package com.motionmouse.app.motion

import com.motionmouse.app.protocol.PacketBuilder

/**
 * Bridges the MotionEngine output to the protocol layer.
 * Keeps the motion engine completely decoupled from the protocol format.
 */
class MotionPacketBuilder {
    fun build(output: MotionOutput): ByteArray {
        return PacketBuilder.buildMotionPacket(
            deltaX = output.deltaX,
            deltaY = output.deltaY,
            timestampMs = output.timestampMs
        )
    }
}
