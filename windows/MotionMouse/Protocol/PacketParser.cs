using System.IO;
using System.Buffers.Binary;
using System.Text;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Protocol;

/// <summary>
/// Parsed representation of an incoming packet from the Android app.
/// </summary>
public abstract record IncomingPacket
{
    /// <summary>
    /// HELLO (0x01) — Android is initiating a connection.
    /// </summary>
    public record Hello(int ProtocolVersion, string DeviceName) : IncomingPacket;

    /// <summary>
    /// MOTION (0x03) — Cursor velocity update.
    /// DeltaX and DeltaY are in pixels per second.
    /// TimestampMs is the Android send time for latency calculation.
    /// </summary>
    public record Motion(float DeltaX, float DeltaY, long TimestampMs) : IncomingPacket;

    /// <summary>
    /// BUTTON (0x04) — Mouse button press or release.
    /// </summary>
    public record Button(byte ButtonId, byte Action) : IncomingPacket;

    /// <summary>
    /// PING (0x05) — Android requesting latency measurement.
    /// </summary>
    public record Ping(long SendTimestampMs) : IncomingPacket;

    /// <summary>
    /// STATUS (0x08) — Battery level, lock state, connection type.
    /// </summary>
    public record Status(
        int BatteryLevel,
        bool IsLocked,
        byte ConnectionType
    ) : IncomingPacket;

    /// <summary>
    /// DISCONNECT (0x09) — Android is disconnecting cleanly.
    /// </summary>
    public record Disconnect : IncomingPacket;

    /// <summary>
    /// An unrecognised packet type — log and ignore.
    /// Forward compatibility for future Android versions.
    /// </summary>
    public record Unknown(byte TypeId) : IncomingPacket;
}

/// <summary>
/// Parses incoming binary packets from the Android application.
///
/// Reads from a Stream using length-prefix framing identical to
/// the Android PacketParser — see that file for framing rationale.
///
/// All methods are async to avoid blocking the UI thread.
/// The caller (transport receive loop) runs on a background thread,
/// but async keeps the pattern consistent and enables future
/// cancellation support.
/// </summary>
public sealed class PacketParser
{
    private readonly ILogger<PacketParser> _logger;

    // Protocol framing constants
    private const int LengthFieldSize = 2;
    private const int MaxPacketSize   = 64;

    public PacketParser(ILogger<PacketParser> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Read and parse the next complete packet from the stream.
    ///
    /// Blocks asynchronously until data is available.
    /// Returns null when the stream is closed cleanly.
    /// Throws IOException on unexpected connection loss.
    /// </summary>
    public async Task<IncomingPacket?> ReadNextPacketAsync(
        Stream stream,
        CancellationToken cancellationToken = default)
    {
        // Step 1: Read 2-byte length prefix
        var lengthBuffer = new byte[LengthFieldSize];
        if (!await ReadFullyAsync(stream, lengthBuffer, cancellationToken))
            return null; // Clean close

        // Big-endian uint16 — matches Android's ByteBuffer.BIG_ENDIAN
        var totalLength = BinaryPrimitives.ReadUInt16BigEndian(lengthBuffer);

        if (totalLength < 3 || totalLength > MaxPacketSize)
        {
            _logger.LogWarning("Invalid packet length {Length} — dropping connection", totalLength);
            return null;
        }

        // Step 2: Read remaining bytes
        var remaining = new byte[totalLength - LengthFieldSize];
        if (!await ReadFullyAsync(stream, remaining, cancellationToken))
            return null;

        // Step 3: Parse type and payload
        var typeId = remaining[0];
        _logger.LogDebug("Received packet: Type=0x{TypeId:X2}, TotalLength={TotalLength}", typeId, totalLength);
        var payload = remaining.AsSpan(1); // Everything after the type byte

        return typeId switch
        {
            PacketType.Hello        => ParseHello(payload),
            PacketType.Motion       => ParseMotion(payload),
            PacketType.Button       => ParseButton(payload),
            PacketType.Ping         => ParsePing(payload),
            PacketType.Status       => ParseStatus(payload),
            PacketType.Disconnect   => new IncomingPacket.Disconnect(),
            _ => new IncomingPacket.Unknown(typeId)
        };
    }

    // --- Individual packet parsers ---

    private IncomingPacket ParseHello(ReadOnlySpan<byte> payload)
    {
        try
        {
            var version = payload[0];
            var nameLen = payload[1];

            if (version == 0xFF)
            {
                _logger.LogError("Protocol version mismatch from Android client");
                return new IncomingPacket.Unknown(PacketType.Hello);
            }

            var name = Encoding.UTF8.GetString(payload.Slice(2, nameLen));
            return new IncomingPacket.Hello(version, name);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to parse HELLO packet");
            return new IncomingPacket.Unknown(PacketType.Hello);
        }
    }

    private IncomingPacket ParseMotion(ReadOnlySpan<byte> payload)
    {
        // Payload: [4] float deltaX, [4] float deltaY, [8] long timestampMs
        // All big-endian per protocol spec
        var deltaX      = BinaryPrimitives.ReadSingleBigEndian(payload[..4]);
        var deltaY      = BinaryPrimitives.ReadSingleBigEndian(payload[4..8]);
        var timestampMs = BinaryPrimitives.ReadInt64BigEndian(payload[8..16]);
        return new IncomingPacket.Motion(deltaX, deltaY, timestampMs);
    }

    private IncomingPacket ParseButton(ReadOnlySpan<byte> payload)
    {
        return new IncomingPacket.Button(payload[0], payload[1]);
    }

    private IncomingPacket ParsePing(ReadOnlySpan<byte> payload)
    {
        var timestamp = BinaryPrimitives.ReadInt64BigEndian(payload[..8]);
        return new IncomingPacket.Ping(timestamp);
    }

    private IncomingPacket ParseStatus(ReadOnlySpan<byte> payload)
    {
        return new IncomingPacket.Status(
            BatteryLevel:   payload[0],
            IsLocked:       payload[1] == 0x01,
            ConnectionType: payload[2]
        );
    }

    // --- Stream reading utility ---

    /// <summary>
    /// Read exactly buffer.Length bytes from stream into buffer.
    /// Returns false on clean stream end (EOF).
    /// Throws IOException on unexpected error.
    /// </summary>
    private static async Task<bool> ReadFullyAsync(
        Stream stream,
        byte[] buffer,
        CancellationToken cancellationToken)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(
                buffer.AsMemory(offset, buffer.Length - offset),
                cancellationToken);

            if (read == 0)
                return false; // Clean EOF

            offset += read;
        }
        return true;
    }
}
