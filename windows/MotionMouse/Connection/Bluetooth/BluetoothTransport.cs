using System.IO;
using InTheHand.Net;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Connection.Bluetooth;

/// <summary>
/// Bluetooth RFCOMM server transport for Windows.
///
/// Registers a Bluetooth service with our Motion Mouse UUID
/// so Android can discover and connect to us without the user
/// manually entering a MAC address or pairing code.
///
/// How RFCOMM service registration works on Windows:
///   We create a BluetoothListener bound to our service UUID.
///   The Windows Bluetooth stack registers this UUID in the
///   Service Discovery Protocol (SDP) database, making it
///   visible to any scanning Bluetooth device.
///   Android's BluetoothDevice.createRfcommSocketToServiceRecord()
///   queries SDP for this UUID and gets the RFCOMM channel number.
///   The connection is then made to that channel.
///
/// Why 32feet.NET (InTheHand.Net.Bluetooth):
///   Windows does not expose Bluetooth RFCOMM server sockets
///   through any managed .NET API. The only options are:
///     1. 32feet.NET — managed wrapper around the Windows
///        Bluetooth socket API (Winsock with AF_BTH)
///     2. Direct P/Invoke to Winsock Bluetooth API
///     3. WinRT Bluetooth APIs — no RFCOMM server in WinRT
///   32feet.NET is the correct choice: well-maintained,
///   MIT licensed, and handles the SDP registration automatically.
///
/// Discoverability:
///   The PC must be discoverable for unpaired Android devices to find it.
///   We set the adapter to discoverable while listening.
///   Paired devices can connect regardless of discoverability.
/// </summary>
public sealed class BluetoothTransport : ITransport
{
    private readonly ILogger<BluetoothTransport> _logger;

    public string Name => "Bluetooth";

    // Motion Mouse service UUID — must match Android's BluetoothDiscovery.MOTION_MOUSE_UUID
    private static readonly Guid ServiceUuid =
        new("8ce255c0-200a-11e0-ac64-0800200c9a66");

    // Service name shown in Windows Bluetooth device manager
    private const string ServiceName = "Motion Mouse";

    private BluetoothListener?  _listener;
    private BluetoothClient?    _client;
    private Stream?             _stream;

    private volatile bool _isClientConnected;
    public bool IsClientConnected => _isClientConnected;

    // Whether Bluetooth is available on this machine
    private bool? _isAvailable;

    public BluetoothTransport(ILogger<BluetoothTransport> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Start RFCOMM server and wait for Android to connect.
    ///
    /// Registers our service UUID in the SDP database.
    /// Makes the adapter discoverable so unpaired phones can find us.
    /// Blocks until a client connects or cancellation is requested.
    /// </summary>
    public async Task StartListeningAsync(CancellationToken cancellationToken)
    {
        if (!IsBluetoothAvailable())
        {
            _logger.LogInformation(
                "Bluetooth not available on this machine — skipping BT transport");

            // Wait for cancellation rather than throwing —
            // the Wi-Fi transport may still win the race.
            await Task.Delay(Timeout.Infinite, cancellationToken);
            return;
        }

        try
        {
            _listener = new BluetoothListener(ServiceUuid)
            {
                ServiceName = ServiceName
            };

            _listener.Start();

            // Make adapter discoverable for unpaired devices
            TrySetDiscoverable(true);

            _logger.LogInformation(
                "Bluetooth RFCOMM server listening. UUID: {Uuid}", ServiceUuid);

            // AcceptBluetoothClient blocks — run on thread pool
            // and wrap with cancellation support
            _client = await Task.Run(
                () => AcceptWithCancellation(_listener, cancellationToken),
                cancellationToken);

            if (_client is null)
                return; // Cancelled

            _stream = _client.GetStream();
            _isClientConnected = true;

            _logger.LogInformation("Bluetooth client connected");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Bluetooth transport error");
            throw new TransportException(
                $"Bluetooth RFCOMM server failed: {ex.Message}", ex);
        }
        finally
        {
            // Always restore discoverability when done listening
            TrySetDiscoverable(false);
        }
    }

    /// <summary>
    /// Accept a Bluetooth connection with cancellation support.
    ///
    /// BluetoothListener.AcceptBluetoothClient() is blocking with
    /// no async or cancellation variant in 32feet.NET.
    /// We run it on the thread pool and use the cancellation token
    /// to close the listener (which unblocks Accept).
    /// </summary>
    private static BluetoothClient? AcceptWithCancellation(
        BluetoothListener listener,
        CancellationToken cancellationToken)
    {
        // Register cancellation to stop the listener
        using var registration = cancellationToken.Register(() =>
        {
            try { listener.Stop(); }
            catch { /* Ignore */ }
        });

        try
        {
            return listener.AcceptBluetoothClient();
        }
        catch (Exception) when (cancellationToken.IsCancellationRequested)
        {
            return null;
        }
    }

    /// <summary>
    /// Send packet bytes to the connected Android client.
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
            _logger.LogWarning(ex, "Bluetooth send failed");
            _isClientConnected = false;
            return false;
        }
    }

    /// <summary>
    /// Return the RFCOMM stream for PacketParser.
    /// Behaves identically to a TCP NetworkStream from the parser's perspective.
    /// </summary>
    public Stream? GetReceiveStream() => _stream;

    /// <summary>
    /// Stop the RFCOMM listener and disconnect any active client.
    /// </summary>
    public void StopListening()
    {
        _isClientConnected = false;

        try { _stream?.Close(); }   catch { /* ignored */ }
        try { _client?.Close(); }   catch { /* ignored */ }
        try { _listener?.Stop(); }  catch { /* ignored */ }

        _stream   = null;
        _client   = null;
        _listener = null;

        _logger.LogInformation("Bluetooth transport stopped");
    }

    /// <summary>
    /// Check whether Bluetooth hardware is available and enabled.
    ///
    /// We cache the result after the first check because hardware
    /// presence doesn't change at runtime.
    /// </summary>
    private bool IsBluetoothAvailable()
    {
        if (_isAvailable.HasValue)
            return _isAvailable.Value;

        try
        {
            var radio = BluetoothRadio.Default;
            _isAvailable = radio is not null && radio.Mode != RadioMode.PowerOff;
        }
        catch
        {
            _isAvailable = false;
        }

        _logger.LogInformation(
            "Bluetooth available: {Available}", _isAvailable);

        return _isAvailable.Value;
    }

    /// <summary>
    /// Attempt to set Bluetooth adapter discoverability.
    /// Non-fatal if it fails — paired devices still work.
    /// </summary>
    private static void TrySetDiscoverable(bool discoverable)
    {
        try
        {
            var radio = BluetoothRadio.Default;
            if (radio is not null)
            {
                radio.Mode = discoverable
                    ? RadioMode.Discoverable
                    : RadioMode.Connectable;
            }
        }
        catch
        {
            // Non-fatal — discoverability is best-effort
        }
    }

    public async ValueTask DisposeAsync()
    {
        StopListening();
        await Task.CompletedTask;
    }
}
