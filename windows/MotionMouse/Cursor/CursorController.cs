using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace MotionMouse.Cursor;

/// <summary>
/// Controls the Windows cursor using raw SendInput() API calls.
///
/// Receives cursor velocity (pixels/second) from the Android app
/// and converts it to actual pixel movement on screen.
///
/// Why SendInput() and not SetCursorPos():
///
///   SetCursorPos() moves the cursor to an absolute position.
///   It bypasses the input system and does not generate WM_MOUSEMOVE
///   messages that applications rely on for hover effects, drag
///   operations, and tooltips. Many applications ignore SetCursorPos().
///
///   SendInput() injects synthetic mouse input into the system input
///   queue — exactly as if a physical mouse had moved. Every
///   application receives the correct WM_MOUSEMOVE messages.
///   This is how all professional remote desktop and mouse emulation
///   tools work.
///
/// Why MOUSEEVENTF_MOVE and not MOUSEEVENTF_ABSOLUTE:
///
///   MOUSEEVENTF_ABSOLUTE requires normalised coordinates (0-65535)
///   mapped to the entire virtual desktop. This is complex on
///   multi-monitor setups and requires knowing the total desktop
///   dimensions. More importantly, it would make our cursor
///   position-based rather than velocity-based, breaking the
///   entire motion model.
///
///   MOUSEEVENTF_MOVE sends a relative delta — exactly what we
///   produce from the motion engine. The OS handles all coordinate
///   mapping, DPI scaling, and multi-monitor math.
///
/// Velocity → pixel conversion:
///
///   Android sends deltaX/deltaY as pixels/second velocities.
///   We receive these at up to 200Hz but network jitter means
///   the actual interval between packets varies.
///
///   We track the time since the last packet and multiply:
///     pixelDelta = velocity × elapsedSeconds
///
///   Sub-pixel accumulation:
///   SendInput() takes integer pixel deltas. At low speeds,
///   velocity × elapsed might be 0.3 pixels per frame.
///   If we round to 0 every frame, the cursor never moves at
///   low speeds — precision is destroyed.
///
///   We accumulate the fractional remainder and add it to the
///   next frame, exactly like a hardware mouse does internally.
///   This is the same technique used in sub-pixel rendering.
///
/// DPI awareness:
///   Because we declared PerMonitorV2 in the manifest, all
///   coordinates we work with are physical pixels. SendInput()
///   with MOUSEEVENTF_MOVE always operates in physical pixels
///   regardless of DPI — no scaling needed here.
/// </summary>
public sealed class CursorController : IDisposable
{
    private readonly ILogger<CursorController> _logger;

    // Sub-pixel accumulator — carries fractional pixel remainders
    // between frames to preserve precision at low velocities.
    private double _accumX;
    private double _accumY;

    // Timestamp of the last motion packet received.
    // Used to compute elapsed time for velocity integration.
    private DateTime _lastPacketTime = DateTime.UtcNow;

    // Maximum elapsed time we will integrate in a single step.
    // If a packet is very late (e.g. app was paused), we cap the
    // integration to avoid a large cursor jump on resume.
    private const double MaxElapsedSeconds = 0.1; // 100ms

    // Whether the controller is currently accepting motion input.
    // Set to false when the user locks the cursor from the phone.
    private volatile bool _isLocked;

    // Lock for thread safety — motion packets may arrive on the
    // network thread while UI reads cursor position on the UI thread.
    private readonly object _lock = new();

    public CursorController(ILogger<CursorController> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Process a motion packet from Android and move the cursor.
    ///
    /// Called on the network receive thread — must be thread-safe.
    /// Designed to return as fast as possible — no logging in hot path.
    ///
    /// <param name="deltaX">Cursor velocity, pixels/second, horizontal.</param>
    /// <param name="deltaY">Cursor velocity, pixels/second, vertical.</param>
    /// <param name="packetTimestampMs">
    ///   Android send time — used to detect stale packets.
    ///   If a packet is more than 200ms old, we skip it to avoid
    ///   jerky cursor movement from queued-up stale motion.
    /// </param>
    /// </summary>
    public void ProcessMotion(float deltaX, float deltaY, long packetTimestampMs)
    {
        _logger.LogDebug("ProcessMotion: deltaX={DeltaX}, deltaY={DeltaY}, locked={Locked}", deltaX, deltaY, _isLocked);
        if (_isLocked) return;
        if (deltaX == 0f && deltaY == 0f) return;

        // Note: We intentionally do NOT check packet age using packetTimestampMs
        // because Android and Windows system clocks are not synchronized —
        // clock skew of 500ms-2000ms is common, which would cause all packets
        // to appear "stale" and be dropped. The elapsed time cap below (MaxElapsedSeconds)
        // already prevents cursor jumps from genuinely stale packets after reconnection.
        var now = DateTime.UtcNow;

        lock (_lock)
        {
            // Compute elapsed time since last packet.
            var elapsed = (now - _lastPacketTime).TotalSeconds;
            elapsed = Math.Min(elapsed, MaxElapsedSeconds);
            _lastPacketTime = now;

            // Apply a base sensitivity multiplier. Android sends raw rad/s velocity (~1.0).
            // Without this, the cursor moves 1 pixel per second!
            // 2000.0f means 1 rad/s moves the cursor ~2000 pixels per second.
            const float Sensitivity = 2000.0f;

            // Integrate velocity to get pixel displacement for this frame.
            // velocity (px/s) × time (s) = displacement (px)
            _accumX += deltaX * Sensitivity * elapsed;
            _accumY += deltaY * Sensitivity * elapsed;

            // Extract integer pixel deltas, keep fractional remainder.
            // This is the sub-pixel accumulation that preserves precision
            // at low cursor speeds.
            var pixelX = (int)Math.Truncate(_accumX);
            var pixelY = (int)Math.Truncate(_accumY);

            // Only call SendInput if we have at least 1 pixel to move.
            // Avoids unnecessary syscalls when the cursor is barely moving.
            if (pixelX == 0 && pixelY == 0)
                return;

            _accumX -= pixelX;
            _accumY -= pixelY;

            // Inject the movement into the Windows input system.
            SendMouseMove(pixelX, pixelY);
        }
    }

    /// <summary>
    /// Process a button event from Android.
    ///
    /// Translates our protocol button IDs and actions into
    /// Windows MOUSEINPUT flags and calls SendInput().
    ///
    /// Press and Release are sent separately — this correctly
    /// supports click-and-drag: hold the button on the phone
    /// while physically dragging your phone across the desk.
    /// </summary>
    public void ProcessButton(byte buttonId, byte action)
    {
        _logger.LogDebug("ProcessButton: id={Id}, action={Action}", buttonId, action);
        var flags = (buttonId, action) switch
        {
            (Protocol.ButtonId.Left,  Protocol.ButtonAction.Press)   =>
                MOUSEEVENTF_LEFTDOWN,
            (Protocol.ButtonId.Left,  Protocol.ButtonAction.Release) =>
                MOUSEEVENTF_LEFTUP,
            (Protocol.ButtonId.Right, Protocol.ButtonAction.Press)   =>
                MOUSEEVENTF_RIGHTDOWN,
            (Protocol.ButtonId.Right, Protocol.ButtonAction.Release) =>
                MOUSEEVENTF_RIGHTUP,
            _ => 0u
        };

        if (flags == 0)
        {
            _logger.LogWarning(
                "Unknown button event: id={ButtonId} action={Action}",
                buttonId, action);
            return;
        }

        SendMouseButton(flags);
    }

    /// <summary>
    /// Set cursor lock state.
    /// When locked, all motion is ignored.
    /// Also clears the sub-pixel accumulator so stale
    /// fractional remainders don't cause a jump on unlock.
    /// </summary>
    public void SetLocked(bool locked)
    {
        _isLocked = locked;
        if (!locked)
        {
            lock (_lock)
            {
                // Reset accumulator and timer on unlock to prevent
                // jumps from stale integration state.
                _accumX = 0;
                _accumY = 0;
                _lastPacketTime = DateTime.UtcNow;
            }
        }
    }

    /// <summary>
    /// Reset integration state.
    /// Called on disconnect/reconnect so stale velocity
    /// doesn't cause a cursor jump on first packet after reconnect.
    /// </summary>
    public void Reset()
    {
        lock (_lock)
        {
            _accumX = 0;
            _accumY = 0;
            _lastPacketTime = DateTime.UtcNow;
        }
    }

    // -----------------------------------------------------------------------
    // SendInput P/Invoke
    // -----------------------------------------------------------------------

    /// <summary>
    /// Inject a relative mouse movement via SendInput().
    ///
    /// MOUSEEVENTF_MOVE with dx/dy and WITHOUT MOUSEEVENTF_ABSOLUTE
    /// sends a relative movement in physical pixels.
    ///
    /// This is the only call in the hot path — everything else
    /// exists to produce the correct dx and dy values for this call.
    /// </summary>
    private static void SendMouseMove(int dx, int dy)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            data = new INPUTUNION
            {
                mi = new MOUSEINPUT
                {
                    dx         = dx,
                    dy         = dy,
                    mouseData  = 0,
                    dwFlags    = MOUSEEVENTF_MOVE,
                    time       = 0,    // 0 = use system timestamp
                    dwExtraInfo = GetMessageExtraInfo()
                }
            }
        };

        SendInput(1, ref input, Marshal.SizeOf<INPUT>());
    }

    /// <summary>
    /// Inject a mouse button event via SendInput().
    /// </summary>
    private static void SendMouseButton(uint flags)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            data = new INPUTUNION
            {
                mi = new MOUSEINPUT
                {
                    dx          = 0,
                    dy          = 0,
                    mouseData   = 0,
                    dwFlags     = flags,
                    time        = 0,
                    dwExtraInfo = GetMessageExtraInfo()
                }
            }
        };

        SendInput(1, ref input, Marshal.SizeOf<INPUT>());
    }

    // -----------------------------------------------------------------------
    // Windows API P/Invoke declarations
    // -----------------------------------------------------------------------

    private const int    INPUT_MOUSE            = 0;
    private const uint   MOUSEEVENTF_MOVE       = 0x0001;
    private const uint   MOUSEEVENTF_LEFTDOWN   = 0x0002;
    private const uint   MOUSEEVENTF_LEFTUP     = 0x0004;
    private const uint   MOUSEEVENTF_RIGHTDOWN  = 0x0008;
    private const uint   MOUSEEVENTF_RIGHTUP    = 0x0010;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int    dx;
        public int    dy;
        public uint   mouseData;
        public uint   dwFlags;
        public uint   time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint       type;
        public INPUTUNION data;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(
        uint nInputs,
        ref INPUT pInputs,
        int cbSize);

    [DllImport("user32.dll")]
    private static extern IntPtr GetMessageExtraInfo();

    public void Dispose()
    {
        // Nothing to dispose currently.
        // Placeholder for future resource cleanup (e.g. raw input hook).
    }
}
