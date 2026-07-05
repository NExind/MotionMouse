using System.Runtime.InteropServices;
using System.Windows;

namespace MotionMouse.Cursor;

/// <summary>
/// Utilities for multi-monitor awareness.
///
/// Motion Mouse works correctly on multi-monitor setups because:
///   1. We use MOUSEEVENTF_MOVE (relative) not MOUSEEVENTF_ABSOLUTE
///   2. Our manifest declares PerMonitorV2 DPI awareness
///
/// This helper provides the total virtual desktop bounds for the
/// Windows UI — used to display monitor info and for future
/// features like per-monitor sensitivity scaling.
///
/// It also provides the method to get the current physical
/// screen dimensions for any informational display.
/// </summary>
public static class MultiMonitorHelper
{
    /// <summary>
    /// Get the total virtual desktop size in physical pixels.
    ///
    /// The virtual desktop is the bounding rectangle of all monitors.
    /// On a single 1920×1080 display: width=1920, height=1080.
    /// On two side-by-side 1920×1080 displays: width=3840, height=1080.
    ///
    /// SM_CXVIRTUALSCREEN and SM_CYVIRTUALSCREEN return these values
    /// in physical pixels when the process is DPI-aware (which we are).
    /// </summary>
    public static (int Width, int Height) GetVirtualDesktopSize()
    {
        var width  = GetSystemMetrics(SM_CXVIRTUALSCREEN);
        var height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        return (width, height);
    }

    /// <summary>
    /// Get the number of connected monitors.
    /// </summary>
    public static int GetMonitorCount()
    {
        return GetSystemMetrics(SM_CMONITORS);
    }

    /// <summary>
    /// Get the primary monitor's resolution in physical pixels.
    /// </summary>
    public static (int Width, int Height) GetPrimaryMonitorSize()
    {
        return (
            GetSystemMetrics(SM_CXSCREEN),
            GetSystemMetrics(SM_CYSCREEN)
        );
    }

    // System metric indices
    private const int SM_CXSCREEN        = 0;
    private const int SM_CYSCREEN        = 1;
    private const int SM_CXVIRTUALSCREEN = 78;
    private const int SM_CYVIRTUALSCREEN = 79;
    private const int SM_CMONITORS       = 80;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);
}
