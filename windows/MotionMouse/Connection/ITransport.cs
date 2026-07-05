using System.IO;
namespace MotionMouse.Connection;

/// <summary>
/// Transport abstraction for the Windows side.
///
/// Mirrors Android's Transport interface — the same philosophy applies:
/// the connection manager never knows which physical transport is active.
/// Wi-Fi TCP, Bluetooth RFCOMM, and future USB all implement this
/// interface identically.
///
/// Key difference from Android's Transport:
///   On Android, the phone INITIATES the connection (client).
///   On Windows, the PC ACCEPTS the connection (server).
///
///   So the Windows transport is a server transport:
///     - It listens on a port/channel
///     - It accepts incoming connections
///     - It manages the accepted client socket
///
///   The interface reflects this with StartListeningAsync()
///   rather than Connect().
///
/// Threading contract:
///   StartListeningAsync() blocks until a client connects or cancellation.
///   SendAsync() is called from the packet dispatch loop.
///   StopListening() may be called from any thread.
///   All implementations must be safe for concurrent Send + Stop.
/// </summary>
public interface ITransport : IAsyncDisposable
{
    /// <summary>
    /// Human-readable name for UI and logging.
    /// </summary>
    string Name { get; }

    /// <summary>
    /// Whether a client is currently connected.
    /// </summary>
    bool IsClientConnected { get; }

    /// <summary>
    /// Start listening for an incoming Android connection.
    ///
    /// Blocks until a client connects, at which point the transport
    /// is ready for Send() and the receive loop can start.
    ///
    /// Throws OperationCanceledException if cancellation is requested.
    /// Throws TransportException if listening fails (port in use, etc.)
    /// </summary>
    Task StartListeningAsync(CancellationToken cancellationToken);

    /// <summary>
    /// Send a packet to the connected Android client.
    ///
    /// Returns false if not connected or send fails.
    /// Non-throwing — caller checks return value.
    /// </summary>
    Task<bool> SendAsync(byte[] data, CancellationToken cancellationToken = default);

    /// <summary>
    /// Get a stream suitable for PacketParser.ReadNextPacketAsync().
    ///
    /// Returns null if not connected.
    /// The stream remains valid until the client disconnects.
    /// </summary>
    Stream? GetReceiveStream();

    /// <summary>
    /// Stop listening and disconnect any active client.
    /// Safe to call multiple times.
    /// </summary>
    void StopListening();
}

/// <summary>
/// Thrown when a transport operation fails in a way the
/// connection manager should handle (port in use, BT unavailable, etc.)
/// </summary>
public sealed class TransportException : Exception
{
    public TransportException(string message) : base(message) { }
    public TransportException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>
/// Describes a connected Android client.
/// Populated after the HELLO handshake completes.
/// </summary>
public sealed record ConnectedClient(
    string DeviceName,
    string Address,
    string TransportName,
    DateTime ConnectedAt
);
