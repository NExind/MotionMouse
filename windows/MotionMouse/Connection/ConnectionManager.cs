using System.IO;
using System.Net;
using MotionMouse.Connection.Bluetooth;
using MotionMouse.Connection.WiFi;
using MotionMouse.Cursor;
using MotionMouse.Protocol;
using MotionMouse.Settings;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Connection;

/// <summary>
/// Manages the full connection lifecycle on the Windows side.
///
/// Architecture (simplified and bulletproof):
///   - Runs a persistent OUTER loop that keeps re-listening after every disconnect
///   - Inside each iteration: races Wi-Fi and Bluetooth to accept one client
///   - After handshake: runs receive loop until client disconnects
///   - After disconnect: outer loop immediately re-listens
///
/// Key design decisions:
///   - A single CancellationTokenSource controls the entire session
///   - The outer loop owns the lifecycle — no re-entrant StartListening() calls
///   - StopListening() is only called when we explicitly want to stop everything
/// </summary>
public sealed class ConnectionManager : IDisposable
{
    private readonly ILogger<ConnectionManager>  _logger;
    private readonly WiFiTransport               _wifiTransport;
    private readonly BluetoothTransport          _bluetoothTransport;
    private readonly UdpDiscoveryServer          _udpDiscovery;
    private readonly CursorController            _cursorController;
    private readonly PacketParser                _packetParser;
    private readonly SettingsRepository          _settingsRepository;

    private CancellationTokenSource? _cts;
    private Task?                    _sessionTask;
    private ConnectedClient?         _connectedClient;
    private ITransport?              _activeTransport;
    private bool                     _isDisposed;

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    public event Action<ConnectionState>? StateChanged;
    public event Action<int, bool>?       StatusReceived;
    public event Action<long>?            LatencyUpdated;

    public ConnectionState CurrentState { get; private set; } = ConnectionState.Idle;
    public ConnectedClient? Client => _connectedClient;

    public ConnectionManager(
        ILogger<ConnectionManager> logger,
        WiFiTransport wifiTransport,
        BluetoothTransport bluetoothTransport,
        UdpDiscoveryServer udpDiscovery,
        CursorController cursorController,
        PacketParser packetParser,
        SettingsRepository settingsRepository)
    {
        _logger             = logger;
        _wifiTransport      = wifiTransport;
        _bluetoothTransport = bluetoothTransport;
        _udpDiscovery       = udpDiscovery;
        _cursorController   = cursorController;
        _packetParser       = packetParser;
        _settingsRepository = settingsRepository;

        _settingsRepository.SettingsChanged += OnSettingsChanged;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /// <summary>
    /// Start the outer listen loop. Non-blocking.
    /// The loop keeps running until Dispose() or DisconnectAsync() stops it.
    /// </summary>
    public void StartListening()
    {
        if (_sessionTask is not null && !_sessionTask.IsCompleted)
        {
            _logger.LogDebug("Already listening — ignoring duplicate StartListening()");
            return;
        }

        _cts = new CancellationTokenSource();
        _sessionTask = Task.Run(() => SessionLoopAsync(_cts.Token));

        _logger.LogInformation("Connection manager started");
    }

    /// <summary>
    /// Disconnect the active client and keep listening for a new one.
    /// </summary>
    public async Task DisconnectAsync()
    {
        if (_activeTransport is not null)
        {
            try
            {
                var packet = PacketBuilder.BuildDisconnect();
                await _activeTransport.SendAsync(packet);
                await Task.Delay(100);
            }
            catch { /* Already disconnected */ }
        }

        // Closing the transport will cause the receive loop to exit,
        // which will cause the outer session loop to re-listen automatically.
        _activeTransport?.StopListening();
    }

    public async Task SendSettingsSyncAsync()
    {
        if (_activeTransport is null || !_activeTransport.IsClientConnected) return;

        var s = _settingsRepository.Current;
        var packet = PacketBuilder.BuildSettingsSync(
            s.SensitivityX, s.SensitivityY, s.SmoothingFactor, s.DeadZone, s.AccelerationExponent);

        await _activeTransport.SendAsync(packet);
    }

    // -----------------------------------------------------------------------
    // Outer session loop — keeps re-listening indefinitely
    // -----------------------------------------------------------------------

    private async Task SessionLoopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("Session loop started");

        while (!cancellationToken.IsCancellationRequested && !_isDisposed)
        {
            SetState(ConnectionState.Listening);
            _udpDiscovery.Start(Environment.MachineName);

            ITransport? winner = null;
            try
            {
                winner = await RaceTransportsAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                _logger.LogInformation("Session loop cancelled during listen");
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error waiting for connection — will retry in 2s");
                SetState(ConnectionState.Idle);
                await Task.Delay(2000, cancellationToken).ConfigureAwait(false);
                continue;
            }

            _udpDiscovery.Stop();

            if (winner is null || cancellationToken.IsCancellationRequested)
                break;

            _activeTransport = winner;

            try
            {
                await PerformHandshakeAndRunAsync(winner, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                _logger.LogInformation("Session loop cancelled during connection");
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Connection ended with error — restarting listener");
            }
            finally
            {
                _logger.LogInformation("Cleaning up transport for next listen cycle");
                _activeTransport = null;
                _connectedClient = null;
                _cursorController.Reset();

                // Stop ONLY the transport that was active
                // (the other was already stopped when it lost the race)
                try { winner.StopListening(); } catch { /* ignore */ }

                SetState(ConnectionState.Idle);
            }

            // Small pause before re-listening so we don't spin if errors are fast
            if (!cancellationToken.IsCancellationRequested)
                await Task.Delay(500, cancellationToken).ConfigureAwait(false);
        }

        _udpDiscovery.Stop();
        SetState(ConnectionState.Idle);
        _logger.LogInformation("Session loop ended");
    }

    // -----------------------------------------------------------------------
    // Transport racing — returns the first transport to accept a connection
    // -----------------------------------------------------------------------

    private async Task<ITransport?> RaceTransportsAsync(CancellationToken cancellationToken)
    {
        var winner = new TaskCompletionSource<ITransport>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        using var wifiCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        using var btCts   = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        var wifiTask = ListenOnTransportAsync(_wifiTransport, winner, wifiCts.Token);
        var btTask   = ListenOnTransportAsync(_bluetoothTransport, winner, btCts.Token);

        ITransport? winningTransport = null;

        try
        {
            winningTransport = await winner.Task.WaitAsync(cancellationToken);

            // Cancel and stop the loser immediately
            if (winningTransport == _wifiTransport)
            {
                btCts.Cancel();
                _bluetoothTransport.StopListening();
                _logger.LogInformation("Wi-Fi won the connection race");
            }
            else
            {
                wifiCts.Cancel();
                _wifiTransport.StopListening();
                _logger.LogInformation("Bluetooth won the connection race");
            }
        }
        finally
        {
            // Always wait for both listener tasks to finish before we proceed,
            // so we know all resources are in a clean state.
            // ContinueWith swallows exceptions — we don't care about loser's errors.
            await Task.WhenAll(
                wifiTask.ContinueWith(_ => Task.CompletedTask),
                btTask.ContinueWith(_ => Task.CompletedTask));
        }

        return winningTransport;
    }

    private async Task ListenOnTransportAsync(
        ITransport transport,
        TaskCompletionSource<ITransport> winner,
        CancellationToken cancellationToken)
    {
        try
        {
            await transport.StartListeningAsync(cancellationToken);

            if (transport.IsClientConnected)
                winner.TrySetResult(transport);
        }
        catch (OperationCanceledException)
        {
            // Lost the race or shutting down — normal
        }
        catch (TransportException ex)
        {
            _logger.LogWarning("{Transport} failed: {Message}", transport.Name, ex.Message);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "{Transport} unexpected error", transport.Name);
        }
    }

    // -----------------------------------------------------------------------
    // Handshake + receive loop — runs for the lifetime of one connection
    // -----------------------------------------------------------------------

    private async Task PerformHandshakeAndRunAsync(
        ITransport transport,
        CancellationToken cancellationToken)
    {
        SetState(ConnectionState.Handshaking);

        var stream = transport.GetReceiveStream()
            ?? throw new InvalidOperationException("No stream after connect");

        // Wait for HELLO with 10s timeout
        using var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        handshakeCts.CancelAfter(TimeSpan.FromSeconds(10));

        IncomingPacket? firstPacket;
        try
        {
            firstPacket = await _packetParser.ReadNextPacketAsync(stream, handshakeCts.Token);
        }
        catch (OperationCanceledException) when (handshakeCts.IsCancellationRequested
                                                 && !cancellationToken.IsCancellationRequested)
        {
            throw new Exception("Handshake timeout — no HELLO received within 10 seconds");
        }

        if (firstPacket is not IncomingPacket.Hello hello)
            throw new Exception($"Expected HELLO, received {firstPacket?.GetType().Name ?? "null"}");

        _logger.LogInformation("HELLO from '{Device}' (v{Version})",
            hello.DeviceName, hello.ProtocolVersion);

        // Send HELLO_ACK
        var ack = PacketBuilder.BuildHelloAck(Environment.MachineName);
        var sent = await transport.SendAsync(ack, cancellationToken);
        if (!sent)
            throw new Exception("Failed to send HELLO_ACK");

        _logger.LogInformation("HELLO_ACK sent — handshake complete");

        _connectedClient = new ConnectedClient(
            hello.DeviceName,
            transport.Name,
            transport.Name,
            DateTime.UtcNow);

        _cursorController.Reset();
        SetState(ConnectionState.Connected);

        // Run receive loop — blocks until client disconnects
        await RunReceiveLoopAsync(transport, stream, cancellationToken);
    }

    // -----------------------------------------------------------------------
    // Receive loop
    // -----------------------------------------------------------------------

    private async Task RunReceiveLoopAsync(
        ITransport transport,
        Stream stream,
        CancellationToken cancellationToken)
    {
        _logger.LogInformation("Receive loop started");

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var packet = await _packetParser.ReadNextPacketAsync(stream, cancellationToken);

                if (packet is null)
                {
                    _logger.LogInformation("Client disconnected cleanly (EOF)");
                    break;
                }

                await DispatchPacketAsync(packet, transport);
            }
        }
        catch (OperationCanceledException)
        {
            _logger.LogInformation("Receive loop cancelled");
            throw; // Let caller handle
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Receive loop error — connection lost");
            // Don't rethrow — session loop will restart listening
        }
        finally
        {
            _logger.LogInformation("Receive loop ended");
        }
    }

    // -----------------------------------------------------------------------
    // Packet dispatch
    // -----------------------------------------------------------------------

    private async Task DispatchPacketAsync(IncomingPacket packet, ITransport transport)
    {
        switch (packet)
        {
            case IncomingPacket.Motion motion:
                _cursorController.ProcessMotion(motion.DeltaX, motion.DeltaY, motion.TimestampMs);
                break;

            case IncomingPacket.Button button:
                _cursorController.ProcessButton(button.ButtonId, button.Action);
                break;

            case IncomingPacket.Ping ping:
                var pong = PacketBuilder.BuildPong(ping.SendTimestampMs);
                await transport.SendAsync(pong);
                var nowMs     = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var latencyMs = Math.Abs((nowMs - ping.SendTimestampMs) / 2);
                RaiseOnUiThread(() => LatencyUpdated?.Invoke(latencyMs));
                break;

            case IncomingPacket.Status status:
                _cursorController.SetLocked(status.IsLocked);
                RaiseOnUiThread(() => StatusReceived?.Invoke(status.BatteryLevel, status.IsLocked));
                break;

            case IncomingPacket.Disconnect:
                _logger.LogInformation("Android sent DISCONNECT");
                throw new OperationCanceledException("Client requested disconnect");

            case IncomingPacket.Unknown unknown:
                _logger.LogDebug("Unknown packet 0x{Type:X2}", unknown.TypeId);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void SetState(ConnectionState state)
    {
        CurrentState = state;
        RaiseOnUiThread(() => StateChanged?.Invoke(state));
        _logger.LogInformation("State → {State}", state);
    }

    private void OnSettingsChanged(AppSettings settings) => _ = SendSettingsSyncAsync();

    private static void RaiseOnUiThread(Action action)
    {
        var dispatcher = System.Windows.Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess())
            action();
        else
            dispatcher.BeginInvoke(action);
    }

    public void Dispose()
    {
        _isDisposed = true;
        _cts?.Cancel();
        _cts?.Dispose();
        _udpDiscovery.Dispose();
        _settingsRepository.SettingsChanged -= OnSettingsChanged;
    }
}

public enum ConnectionState
{
    Idle,
    Listening,
    Handshaking,
    Connected
}
