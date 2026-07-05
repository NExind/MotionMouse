using System.Text.Json.Serialization;

namespace MotionMouse.Settings;

/// <summary>
/// Immutable snapshot of all Windows-side application settings.
///
/// Mirrors Android's MotionSettings in structure.
/// The Windows app is the authoritative settings source —
/// it can push settings to Android via SETTINGS_SYNC packets.
///
/// Stored as JSON in:
///   %AppData%\MotionMouse\settings.json
///
/// All values are validated by SettingsRepository before
/// they reach this record. The ranges here are informational
/// and match the Android-side limits exactly.
///
/// Using a record means:
///   - Value equality (two AppSettings with same values are equal)
///   - Non-destructive mutation via 'with' expressions
///   - Automatic ToString() for logging
///   - Thread-safe reads (immutable)
/// </summary>
public sealed record AppSettings
{
    // -----------------------------------------------------------------------
    // Motion settings — synced to Android via SETTINGS_SYNC
    // -----------------------------------------------------------------------

    /// <summary>
    /// Overall cursor speed multiplier for the X axis.
    /// Range: 0.1 – 5.0. Default: 1.0
    /// </summary>
    [JsonPropertyName("sensitivityX")]
    public float SensitivityX { get; init; } = DefaultSensitivity;

    /// <summary>
    /// Overall cursor speed multiplier for the Y axis.
    /// Range: 0.1 – 5.0. Default: 1.0
    /// </summary>
    [JsonPropertyName("sensitivityY")]
    public float SensitivityY { get; init; } = DefaultSensitivity;

    /// <summary>
    /// Exponential moving average smoothing factor.
    /// Range: 0.0 (raw) – 0.95 (very smooth). Default: 0.6
    /// </summary>
    public float SmoothingFactor { get; init; } = DefaultSmoothing;

    /// <summary>
    /// Dead zone threshold in rad/s.
    /// Range: 0.005 – 0.1. Default: 0.02
    /// </summary>
    public float DeadZone { get; init; } = DefaultDeadZone;

    /// <summary>
    /// Power curve exponent for adaptive acceleration.
    /// Range: 1.0 (linear) – 3.0 (aggressive). Default: 1.8
    /// </summary>
    public float AccelerationExponent { get; init; } = DefaultAccelerationExponent;

    // -----------------------------------------------------------------------
    // Connection settings — Windows only
    // -----------------------------------------------------------------------

    /// <summary>
    /// Whether to start Motion Mouse automatically with Windows.
    /// Managed via a registry run key.
    /// Default: false
    /// </summary>
    public bool StartWithWindows { get; init; } = false;

    /// <summary>
    /// Whether to minimise to system tray instead of taskbar on close.
    /// Default: true — expected behaviour for a tray application.
    /// </summary>
    public bool MinimiseToTray { get; init; } = true;

    /// <summary>
    /// Whether to show a toast notification on connect/disconnect.
    /// Default: true
    /// </summary>
    public bool ShowConnectionToasts { get; init; } = true;

    // -----------------------------------------------------------------------
    // Validation ranges
    // -----------------------------------------------------------------------

    public const float DefaultSensitivity           = 0.01f;
    public const float DefaultSmoothing             = 0.4f;
    public const float DefaultDeadZone              = 0.05f;
    public const float DefaultAccelerationExponent  = 1.8f;

    public const float SensitivityMin           = 0.001f;
    public const float SensitivityMax           = 1.5f;
    public const float SmoothingMin             = 0.0f;
    public const float SmoothingMax             = 0.95f;
    public const float DeadZoneMin              = 0.005f;
    public const float DeadZoneMax              = 0.1f;
    public const float AccelerationExponentMin  = 1.0f;
    public const float AccelerationExponentMax  = 3.0f;

    /// <summary>
    /// Factory defaults — returned by SettingsRepository on first run
    /// or after a reset.
    /// </summary>
    public static AppSettings Default => new();

    /// <summary>
    /// Returns a validated copy with all values clamped to legal ranges.
    /// Called by SettingsRepository after deserialising from disk.
    /// </summary>
    public AppSettings Validated() => this with
    {
        SensitivityX = Math.Clamp(SensitivityX,
            SensitivityMin, SensitivityMax),

        SensitivityY = Math.Clamp(SensitivityY,
            SensitivityMin, SensitivityMax),

        SmoothingFactor = Math.Clamp(SmoothingFactor,
            SmoothingMin, SmoothingMax),

        DeadZone = Math.Clamp(DeadZone,
            DeadZoneMin, DeadZoneMax),

        AccelerationExponent = Math.Clamp(AccelerationExponent,
            AccelerationExponentMin, AccelerationExponentMax)
    };
}
