using System.Buffers.Binary;
using System.Text;

namespace MotionMouse.Protocol;

/// <summary>
/// Builds outgoing binary packets to send to the Android app.
///
/// Mirror of the Android PacketBuilder — same format, opposite direction.
/// Only builds the packet types that Windows sends:
///   HELLO_ACK, PONG, SETTINGS_SYNC, DISCONNECT
///
/// All methods return pre-allocated byte arrays.
/// These are called infrequently enough that allocation is not a concern.
/// (Unlike Android's MOTION builder which runs at 200Hz.)
/// </summary>
public static class PacketBuilder
{
    private const int LengthFieldSize = 2;
    private const int TypeFieldSize   = 1;
    private const int HeaderSize      = LengthFieldSize + TypeFieldSize;

    /// <summary>
    /// HELLO_ACK (0x02) — Windows → Android
    /// Sent in response to HELLO to complete the handshake.
    ///
    /// Payload: [1] version, [1] nameLen, [N] pcName (UTF-8)
    /// </summary>
    public static byte[] BuildHelloAck(string pcName)
    {
        var nameBytes   = Encoding.UTF8.GetBytes(pcName[..Math.Min(pcName.Length, 32)]);
        var payloadSize = 1 + 1 + nameBytes.Length;
        var totalSize   = HeaderSize + payloadSize;
        var packet      = new byte[totalSize];

        BinaryPrimitives.WriteUInt16BigEndian(packet, (ushort)totalSize);
        packet[2] = PacketType.HelloAck;
        packet[3] = 0x02;                          // Protocol version = 2
        packet[4] = (byte)nameBytes.Length;
        nameBytes.CopyTo(packet, 5);

        return packet;
    }

    /// <summary>
    /// PONG (0x06) — Windows → Android
    /// Echo the original timestamp back for RTT calculation.
    ///
    /// Payload: [8] originalSendTimestampMs (int64, big-endian)
    /// </summary>
    public static byte[] BuildPong(long originalTimestampMs)
    {
        var totalSize = HeaderSize + 8;
        var packet    = new byte[totalSize];

        BinaryPrimitives.WriteUInt16BigEndian(packet, (ushort)totalSize);
        packet[2] = PacketType.Pong;
        BinaryPrimitives.WriteInt64BigEndian(packet.AsSpan(3), originalTimestampMs);

        return packet;
    }

    /// <summary>
    /// SETTINGS_SYNC (0x07) — Windows → Android
    /// Push settings from the Windows UI to the Android motion engine.
    ///
    /// Payload: [4] sensitivityX, [4] sensitivityY, [4] smoothing, [4] deadZone, [4] accelExponent
    /// </summary>
    public static byte[] BuildSettingsSync(
        float sensitivityX,
        float sensitivityY,
        float smoothingFactor,
        float deadZone,
        float accelerationExponent)
    {
        var packet = new byte[25];  // 2 (len) + 1 (type) + 20 (payload) = 25
        const ushort payloadSize = 20;

        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0), payloadSize);
        packet[2] = PacketType.SettingsSync;

        // Big-endian floats (network byte order)
        BinaryPrimitives.WriteSingleBigEndian(packet.AsSpan(3),  sensitivityX);
        BinaryPrimitives.WriteSingleBigEndian(packet.AsSpan(7),  sensitivityY);
        BinaryPrimitives.WriteSingleBigEndian(packet.AsSpan(11), smoothingFactor);
        BinaryPrimitives.WriteSingleBigEndian(packet.AsSpan(15), deadZone);
        BinaryPrimitives.WriteSingleBigEndian(packet.AsSpan(19), accelerationExponent);

        return packet;
    }

    /// <summary>
    /// DISCONNECT (0x09) — Windows → Android
    /// Notify Android of intentional disconnect before closing socket.
    /// No payload.
    /// </summary>
    public static byte[] BuildDisconnect()
    {
        var packet = new byte[HeaderSize];
        BinaryPrimitives.WriteUInt16BigEndian(packet, (ushort)HeaderSize);
        packet[2] = PacketType.Disconnect;
        return packet;
    }
}
