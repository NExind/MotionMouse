using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Connection.WiFi;

/// <summary>
/// UDP broadcast server — the Windows side of Wi-Fi discovery.
///
/// Protocol (from PROTOCOL.md):
///   Windows broadcasts a JSON announcement every 2 seconds.
///   Android listens, receives it, sends a reply, then opens a TCP connection.
///
/// This class only handles the UDP discovery phase.
/// The actual TCP data connection is handled by WiFiTransport.
///
/// Broadcast behaviour:
///   We broadcast to 255.255.255.255 AND to each active interface's
///   directed (subnet) broadcast address on port 41234.
///
/// Implementation note — raw Socket instead of UdpClient:
///   UdpClient's EnableBroadcast property and its interaction with a
///   manually-bound underlying Client socket has a long history of
///   unreliable behaviour on Windows (sends report success but packets
///   never actually leave the machine — see dotnet/runtime #49711,
///   #83525, #118055). We use two raw Sockets instead — one dedicated
///   to sending (with SO_BROADCAST set immediately on creation, before
///   any bind or send), and one dedicated to receiving replies (bound
///   to the discovery port so Android's reply reaches us). This avoids
///   UdpClient's internal state-tracking entirely.
///
/// Why we also listen for replies:
///   The Android reply tells us the phone's device name
///   before the TCP connection is established. We surface
///   this in the UI so the user sees "Pixel 8 Pro connecting…"
///   rather than just an IP address.
/// </summary>
public sealed class UdpDiscoveryServer : IDisposable
{
    private readonly ILogger<UdpDiscoveryServer> _logger;

    // Port constants matching PROTOCOL.md
    private const int DiscoveryPort = 41234;
    private const int DataPort      = 41235;

    // Broadcast interval — every 2 seconds per protocol spec
    private const int BroadcastIntervalMs = 2000;

    // Maximum UDP reply size we will read
    private const int MaxReplySize = 512;

    private Socket?             _sendSocket;
    private Socket?             _receiveSocket;
    private CancellationTokenSource? _cts;
    private Task?               _broadcastTask;
    private Task?               _receiveTask;

    // PC name sent in announcements — set on start
    private string _pcName = Environment.MachineName;

    // Raised when an Android device replies to our broadcast.
    // Provides the device name from the reply packet.
    public event Action<string, IPAddress>? DeviceReplied;

    public UdpDiscoveryServer(ILogger<UdpDiscoveryServer> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Start broadcasting and listening for replies.
    /// Non-blocking — runs on background tasks.
    /// </summary>
    public void Start(string? pcName = null)
    {
        _pcName = pcName ?? Environment.MachineName;
        _cts = new CancellationTokenSource();

        // --- Dedicated SEND socket ---
        // SO_BROADCAST is set immediately on creation, before any bind or
        // send call — this is the order that reliably works on Windows.
        _sendSocket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        _sendSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.Broadcast, true);
        _sendSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        // Bind to ANY available port — we don't need a fixed source port for sending,
        // and NOT binding to DiscoveryPort here avoids any conflict with the
        // receive socket below, which does need that exact port.
        _sendSocket.Bind(new IPEndPoint(IPAddress.Any, 0));

        // --- Dedicated RECEIVE socket ---
        // Bound to DiscoveryPort so Android's reply (sent back to that port)
        // reaches us.
        _receiveSocket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        _receiveSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _receiveSocket.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));

        _broadcastTask = RunBroadcastLoopAsync(_cts.Token);
        _receiveTask   = RunReceiveLoopAsync(_cts.Token);

        _logger.LogInformation(
            "UDP discovery started. Broadcasting as '{PcName}' on port {Port}",
            _pcName, DiscoveryPort);
    }

    /// <summary>
    /// Stop broadcasting. Safe to call multiple times.
    /// </summary>
    public void Stop()
    {
        _cts?.Cancel();
        _sendSocket?.Close();
        _receiveSocket?.Close();
        _logger.LogInformation("UDP discovery stopped");
    }

    /// <summary>
    /// Broadcast loop — sends an announcement to every target every
    /// BroadcastIntervalMs until cancelled.
    /// </summary>
    private async Task RunBroadcastLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            var announcement = BuildAnnouncement();
            var announcementBytes = Encoding.UTF8.GetBytes(announcement);

            while (!cancellationToken.IsCancellationRequested)
            {
                // Send to the limited broadcast address (255.255.255.255) AND
                // to each active interface's directed (subnet) broadcast address.
                //
                // Why both: 255.255.255.255 alone is ambiguous on machines with
                // multiple network adapters (Wi-Fi, Ethernet, VPN, Hyper-V/WSL
                // virtual adapters, Bluetooth PAN, etc.) — Windows may send it
                // out an adapter that never reaches the phone's actual subnet.
                // Directed broadcasts (e.g. 192.168.1.255) are sent explicitly
                // on the subnet we know the phone is likely on, so they reach
                // their target even when the limited broadcast doesn't.
                var targets = GetBroadcastTargets();
                var sentCount = 0;

                foreach (var target in targets)
                {
                    try
                    {
                        _sendSocket!.SendTo(
                            announcementBytes,
                            new IPEndPoint(target, DiscoveryPort));
                        sentCount++;
                    }
                    catch (Exception ex) when (!cancellationToken.IsCancellationRequested)
                    {
                        _logger.LogWarning(ex, "Failed to send UDP broadcast to {Target}", target);
                    }
                }

                _logger.LogDebug(
                    "Sent UDP broadcast announcement to {Sent}/{Total} target(s)",
                    sentCount, targets.Count);

                await Task.Delay(BroadcastIntervalMs, cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "UDP broadcast loop error");
        }
    }

    /// <summary>
    /// Receive loop — listens for Android replies on the discovery port
    /// until cancelled. Runs independently of the broadcast loop so a
    /// reply can be handled at any time, not just in a window after
    /// each send.
    /// </summary>
    private async Task RunReceiveLoopAsync(CancellationToken cancellationToken)
    {
        var buffer = new byte[MaxReplySize];

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    var result = await _receiveSocket!.ReceiveFromAsync(
                        buffer,
                        SocketFlags.None,
                        new IPEndPoint(IPAddress.Any, 0),
                        cancellationToken);

                    var senderEndPoint = (IPEndPoint)result.RemoteEndPoint;
                    var data = new byte[result.ReceivedBytes];
                    Array.Copy(buffer, data, result.ReceivedBytes);

                    HandleReply(data, senderEndPoint.Address);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex) when (!cancellationToken.IsCancellationRequested)
                {
                    _logger.LogDebug(ex, "Error receiving UDP reply — continuing");
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown
        }
    }

    /// <summary>
    /// Parse and handle a reply from an Android device.
    ///
    /// Expected format:
    /// {
    ///   "type": "MOTION_MOUSE_REPLY",
    ///   "version": 1,
    ///   "device_name": "Pixel 8 Pro"
    /// }
    /// </summary>
    private void HandleReply(byte[] data, IPAddress senderAddress)
    {
        try
        {
            var json = Encoding.UTF8.GetString(data);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (root.GetProperty("type").GetString() != "MOTION_MOUSE_REPLY")
                return;

            var deviceName = root.TryGetProperty("device_name", out var nameProp)
                ? nameProp.GetString() ?? "Android Device"
                : "Android Device";

            _logger.LogInformation(
                "Android device replied: '{DeviceName}' at {Address}",
                deviceName, senderAddress);

            DeviceReplied?.Invoke(deviceName, senderAddress);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Could not parse UDP reply — ignoring");
        }
    }

    /// <summary>
    /// Build the JSON announcement packet per PROTOCOL.md.
    /// </summary>
    private string BuildAnnouncement() =>
        JsonSerializer.Serialize(new
        {
            type     = "MOTION_MOUSE_ANNOUNCE",
            version  = 1,
            pc_name  = _pcName,
            tcp_port = DataPort
        });

    /// <summary>
    /// Compute broadcast targets: the limited broadcast address plus the
    /// directed (subnet) broadcast address for every active, non-loopback
    /// IPv4 network interface.
    ///
    /// Rationale: 255.255.255.255 alone is unreliable on machines with
    /// multiple adapters (Wi-Fi + Ethernet + VPN + Hyper-V/WSL virtual
    /// adapters are extremely common even on a "normal" home PC) — Windows
    /// may route it out an adapter that doesn't reach the phone's subnet
    /// at all. Computing each interface's own directed broadcast address
    /// (e.g. 192.168.1.255 for a 192.168.1.0/24 Wi-Fi adapter) and sending
    /// to those explicitly reaches the phone regardless of which adapter
    /// Windows would have picked for the ambiguous limited broadcast.
    /// </summary>
    private static List<IPAddress> GetBroadcastTargets()
    {
        var targets = new List<IPAddress> { IPAddress.Broadcast };

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var ua in nic.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ua.Address)) continue;
                    if (ua.IPv4Mask is null) continue;

                    var directedBroadcast = GetDirectedBroadcastAddress(ua.Address, ua.IPv4Mask);
                    if (directedBroadcast is not null)
                        targets.Add(directedBroadcast);
                }
            }
        }
        catch (Exception)
        {
            // If interface enumeration fails for any reason, we still have
            // the limited broadcast address as a fallback — never throw here.
        }

        return targets.Distinct().ToList();
    }

    /// <summary>
    /// Compute the directed broadcast address for a given IPv4 address and
    /// subnet mask — e.g. 192.168.1.42 / 255.255.255.0 -> 192.168.1.255.
    /// </summary>
    private static IPAddress? GetDirectedBroadcastAddress(IPAddress address, IPAddress mask)
    {
        var addressBytes = address.GetAddressBytes();
        var maskBytes    = mask.GetAddressBytes();

        if (addressBytes.Length != maskBytes.Length) return null;

        var broadcastBytes = new byte[addressBytes.Length];
        for (var i = 0; i < addressBytes.Length; i++)
            broadcastBytes[i] = (byte)(addressBytes[i] | (byte)~maskBytes[i]);

        return new IPAddress(broadcastBytes);
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
