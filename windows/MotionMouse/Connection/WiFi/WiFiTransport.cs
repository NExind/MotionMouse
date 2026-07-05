using System.IO;
using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Connection.WiFi;

/// <summary>
/// TCP server transport for Wi-Fi connections.
///
/// Listens on port 41235 for incoming TCP connections from Android.
/// Accepts one client at a time — Motion Mouse is a 1:1 relationship
/// between one phone and one PC.
///
/// Socket configuration mirrors Android's WifiTransport:
///   NoDelay = true        → disable Nagle, send immediately
///   KeepAlive = true      → detect dead connections
///   ReceiveTimeout = 10s  → detect silent disconnects
///   SendTimeout = 1s      → don't block UI on failed send
///
/// The receive stream is exposed directly to PacketParser —
/// no intermediate buffering. PacketParser's ReadFullyAsync handles
/// the stream correctly regardless of TCP segmentation.
///
/// Thread safety:
///   StartListeningAsync runs on a background task.
///   SendAsync may be called from the packet dispatcher.
///   StopListening may be called from the UI thread.
///   All of these are safe due to TcpClient/NetworkStream's
///   internal thread safety for concurrent reads and writes.
/// </summary>
public sealed class WiFiTransport : ITransport
{
    private readonly ILogger<WiFiTransport> _logger;

    public string Name => "Wi-Fi";

    private TcpListener?  _listener;
    private TcpClient?    _client;
    private NetworkStream? _stream;

    private volatile bool _isClientConnected;
    public bool IsClientConnected => _isClientConnected;

    // Data port per PROTOCOL.md
    private const int DataPort             = 41235;
    private const int ReceiveTimeoutMs     = 0;  // No timeout — rely on KeepAlive + PING packets
    private const int SendTimeoutMs        = 1_000;   // 1s
    private const int SendBufferSize       = 65_536;
    private const int ReceiveBufferSize    = 4_096;

    public WiFiTransport(ILogger<WiFiTransport> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Start TCP listener and wait for Android to connect.
    ///
    /// Blocks until:
    ///   - A client connects (returns normally)
    ///   - Cancellation is requested (throws OperationCanceledException)
    ///   - An error occurs (throws TransportException)
    ///
    /// After this returns, IsClientConnected is true and
    /// GetReceiveStream() returns a valid stream.
    /// </summary>
    public async Task StartListeningAsync(CancellationToken cancellationToken)
    {
        try
        {
            // Listen on all interfaces — works for both LAN and hotspot
            _listener = new TcpListener(IPAddress.Any, DataPort);
            _listener.Server.SetSocketOption(
                SocketOptionLevel.Socket,
                SocketOptionName.ReuseAddress,
                true);
            _listener.Start();

            _logger.LogInformation(
                "Wi-Fi transport listening on port {Port}", DataPort);

            // AcceptTcpClientAsync respects cancellation correctly
            // by throwing OperationCanceledException when cancelled
            _client = await _listener.AcceptTcpClientAsync(cancellationToken);

            // Configure socket for low latency
            _client.NoDelay                  = true;
            _client.ReceiveTimeout           = ReceiveTimeoutMs;
            _client.SendTimeout              = SendTimeoutMs;
            _client.SendBufferSize           = SendBufferSize;
            _client.ReceiveBufferSize        = ReceiveBufferSize;
            _client.Client.SetSocketOption(
                SocketOptionLevel.Socket,
                SocketOptionName.KeepAlive,
                true);

            _stream = _client.GetStream();
            _isClientConnected = true;

            var clientAddress = (_client.Client.RemoteEndPoint as IPEndPoint)?.Address;
            _logger.LogInformation(
                "Wi-Fi client connected from {Address}", clientAddress);
        }
        catch (OperationCanceledException)
        {
            throw; // Let caller handle cancellation
        }
        catch (Exception ex)
        {
            throw new TransportException($"Wi-Fi transport failed to start: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Send packet bytes to the connected Android client.
    /// Non-blocking from the caller's perspective — uses async write.
    /// Returns false if not connected or if the send fails.
    /// </summary>
    public async Task<bool> SendAsync(
        byte[] data,
        CancellationToken cancellationToken = default)
    {
        if (!_isClientConnected || _stream is null)
            return false;

        try
        {
            await _stream.WriteAsync(data, cancellationToken);
            await _stream.FlushAsync(cancellationToken);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Wi-Fi send failed");
            _isClientConnected = false;
            return false;
        }
    }

    /// <summary>
    /// Return the underlying stream for PacketParser to read from.
    /// </summary>
    public Stream? GetReceiveStream() => _stream;

    /// <summary>
    /// Stop listening and disconnect the active client.
    /// </summary>
    public void StopListening()
    {
        _isClientConnected = false;

        try { _stream?.Close(); }    catch { /* ignored */ }
        try { _client?.Close(); }    catch { /* ignored */ }
        try { _listener?.Stop(); }   catch { /* ignored */ }

        _stream   = null;
        _client   = null;
        _listener = null;

        _logger.LogInformation("Wi-Fi transport stopped");
    }

    public async ValueTask DisposeAsync()
    {
        StopListening();
        await Task.CompletedTask;
    }
}
