using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

namespace MotionMouse.Settings;

/// <summary>
/// Persists and retrieves AppSettings to/from disk.
///
/// Storage:
///   %AppData%\MotionMouse\settings.json
///   Using %AppData% (roaming) means settings follow the user
///   on domain-joined machines. Non-critical for V1 but correct.
///
/// Format: JSON via System.Text.Json (built into .NET — no extra dependency).
///
/// Thread safety:
///   All public methods are thread-safe.
///   SettingsChanged is raised on whatever thread calls Save().
///   ConnectionManager marshals this to the UI thread if needed.
///
/// StartWithWindows:
///   Managed via HKCU\Software\Microsoft\Windows\CurrentVersion\Run.
///   We use the current user key (HKCU) not HKLM — no admin required.
///   This is the correct, standard mechanism for auto-start apps.
/// </summary>
public sealed class SettingsRepository
{
    private readonly ILogger<SettingsRepository> _logger;

    // Settings file location
    private static readonly string SettingsDirectory =
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "MotionMouse");

    private static readonly string SettingsFilePath =
        Path.Combine(SettingsDirectory, "settings.json");

    // Registry key for startup
    private const string StartupRegistryKey =
        @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string StartupValueName = "MotionMouse";

    // JSON serializer options — consistent across load/save
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented             = true,
        PropertyNamingPolicy      = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition    = JsonIgnoreCondition.Never,
        NumberHandling            = JsonNumberHandling.AllowReadingFromString
    };

    // In-memory current settings
    private AppSettings _current;
    private readonly object _lock = new();

    /// <summary>
    /// Current settings snapshot.
    /// Replaced atomically on every save — safe to read from any thread.
    /// </summary>
    public AppSettings Current
    {
        get { lock (_lock) return _current; }
        private set { lock (_lock) _current = value; }
    }

    /// <summary>
    /// Raised after settings are saved.
    /// Subscribers (ConnectionManager) push changes to Android.
    /// </summary>
    public event Action<AppSettings>? SettingsChanged;

    public SettingsRepository(ILogger<SettingsRepository> logger)
    {
        _logger  = logger;
        _current = AppSettings.Default;
    }

    /// <summary>
    /// Load settings from disk.
    /// Called once on application startup.
    /// Returns defaults if the file doesn't exist or is corrupt.
    /// </summary>
    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsFilePath))
            {
                _logger.LogInformation(
                    "No settings file found — using defaults");
                Current = AppSettings.Default;
                return Current;
            }

            var json     = File.ReadAllText(SettingsFilePath);
            var settings = JsonSerializer.Deserialize<AppSettings>(json, JsonOptions)
                ?? AppSettings.Default;

            // Validate after deserialising — handles corrupt or
            // out-of-range values from old versions
            Current = settings.Validated();

            _logger.LogInformation(
                "Settings loaded from {Path}", SettingsFilePath);

            return Current;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex,
                "Failed to load settings — using defaults");
            Current = AppSettings.Default;
            return Current;
        }
    }

    /// <summary>
    /// Save settings to disk and raise SettingsChanged.
    ///
    /// Validates before saving — callers don't need to validate.
    /// Writes atomically via a temp file rename to prevent
    /// corrupt settings files if the app is killed mid-write.
    /// </summary>
    public void Save(AppSettings settings)
    {
        var validated = settings.Validated();

        try
        {
            Directory.CreateDirectory(SettingsDirectory);

            var json    = JsonSerializer.Serialize(validated, JsonOptions);
            var tmpPath = SettingsFilePath + ".tmp";

            // Write to temp file first
            File.WriteAllText(tmpPath, json);

            // Atomic rename — prevents partial writes being read
            File.Move(tmpPath, SettingsFilePath, overwrite: true);

            Current = validated;

            _logger.LogInformation("Settings saved to {Path}", SettingsFilePath);

            // Notify subscribers (ConnectionManager sends SETTINGS_SYNC)
            SettingsChanged?.Invoke(validated);

            // Apply startup registry setting
            ApplyStartWithWindows(validated.StartWithWindows);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to save settings");
        }
    }

    /// <summary>
    /// Reset all settings to factory defaults.
    /// Deletes the settings file and raises SettingsChanged.
    /// </summary>
    public void ResetToDefaults()
    {
        try
        {
            if (File.Exists(SettingsFilePath))
                File.Delete(SettingsFilePath);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not delete settings file");
        }

        Current = AppSettings.Default;
        SettingsChanged?.Invoke(Current);
        ApplyStartWithWindows(false);

        _logger.LogInformation("Settings reset to defaults");
    }

    // -----------------------------------------------------------------------
    // Start with Windows
    // -----------------------------------------------------------------------

    /// <summary>
    /// Read/write/delete the startup registry value.
    ///
    /// HKCU run key is the standard, admin-free mechanism for
    /// auto-starting user applications on Windows login.
    ///
    /// Value: full path to our executable with --minimised flag
    /// so the app starts in tray without showing the main window.
    /// </summary>
    private void ApplyStartWithWindows(bool enable)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                StartupRegistryKey, writable: true);

            if (key is null)
            {
                _logger.LogWarning("Could not open startup registry key");
                return;
            }

            if (enable)
            {
                var exePath = Environment.ProcessPath ?? string.Empty;
                if (string.IsNullOrEmpty(exePath))
                {
                    _logger.LogWarning("Could not determine executable path for startup");
                    return;
                }

                // --minimised flag tells App.xaml.cs to start in tray
                key.SetValue(StartupValueName, $"\"{exePath}\" --minimised");
                _logger.LogInformation("Start with Windows enabled");
            }
            else
            {
                key.DeleteValue(StartupValueName, throwOnMissingValue: false);
                _logger.LogInformation("Start with Windows disabled");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to update startup registry");
        }
    }

    /// <summary>
    /// Check whether the startup registry value currently exists.
    /// Used to initialise the StartWithWindows toggle in the UI.
    /// </summary>
    public bool IsStartWithWindowsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(StartupRegistryKey);
            return key?.GetValue(StartupValueName) is not null;
        }
        catch
        {
            return false;
        }
    }
}
