using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using MotionMouse.Connection;
using MotionMouse.Settings;

namespace MotionMouse.UI.ViewModels;

/// <summary>
/// ViewModel for SettingsWindow.
///
/// Loads current settings on construction.
/// Saves immediately on every property change — no explicit Save button.
/// This gives the user live preview of every setting change while
/// Motion Mouse is active.
///
/// Settings that affect the Android motion engine are pushed via
/// SETTINGS_SYNC automatically when SettingsRepository fires
/// SettingsChanged (which ConnectionManager is subscribed to).
///
/// The ViewModel does not directly reference the transport layer —
/// settings propagation is handled by the ConnectionManager
/// subscription in App.xaml.cs wiring.
/// </summary>
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly SettingsRepository _settingsRepository;
    private readonly ConnectionManager  _connectionManager;

    // Suppress saving during initial load to avoid a spurious save
    // before values are fully populated from disk.
    private bool _isLoading = true;

    // -----------------------------------------------------------------------
    // Motion settings
    // -----------------------------------------------------------------------

    [ObservableProperty] private float  _sensitivityX;
    [ObservableProperty] private float  _sensitivityY;
    [ObservableProperty] private float  _smoothing;
    [ObservableProperty] private float  _deadZone;

    // Display strings — formatted for the UI labels
    [ObservableProperty] private string _sensitivityXDisplay = "";
    [ObservableProperty] private string _sensitivityYDisplay = "";
    [ObservableProperty] private string _smoothingDisplay   = "";
    [ObservableProperty] private string _deadZoneDisplay    = "";

    // -----------------------------------------------------------------------
    // Behaviour settings
    // -----------------------------------------------------------------------

    [ObservableProperty] private bool _startWithWindows;
    [ObservableProperty] private bool _minimiseToTray;
    [ObservableProperty] private bool _showConnectionToasts;

    // -----------------------------------------------------------------------
    // Sync state
    // -----------------------------------------------------------------------

    [ObservableProperty] private bool   _canSync       = false;
    [ObservableProperty] private string _syncStatusText = "";
    [ObservableProperty] private bool   _hasSyncStatus  = false;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SettingsViewModel(
        SettingsRepository settingsRepository,
        ConnectionManager connectionManager)
    {
        _settingsRepository = settingsRepository;
        _connectionManager  = connectionManager;

        // Subscribe to connection state to enable/disable Sync button
        _connectionManager.StateChanged += OnConnectionStateChanged;
        CanSync = _connectionManager.CurrentState == ConnectionState.Connected;

        LoadFromSettings(_settingsRepository.Current);
        _isLoading = false;
    }

    // -----------------------------------------------------------------------
    // Property change handlers — save on every change
    // -----------------------------------------------------------------------

    partial void OnSensitivityXChanged(float value)
    {
        SensitivityXDisplay = $"{value:F2}x";
        SaveIfLoaded();
    }

    partial void OnSensitivityYChanged(float value)
    {
        SensitivityYDisplay = $"{value:F2}x";
        SaveIfLoaded();
    }

    partial void OnSmoothingChanged(float value)
    {
        SmoothingDisplay = $"{value * 100:F0}%";
        SaveIfLoaded();
    }

    partial void OnDeadZoneChanged(float value)
    {
        DeadZoneDisplay = $"{value:F3}";
        SaveIfLoaded();
    }

    partial void OnStartWithWindowsChanged(bool value)    => SaveIfLoaded();
    partial void OnMinimiseToTrayChanged(bool value)      => SaveIfLoaded();
    partial void OnShowConnectionToastsChanged(bool value) => SaveIfLoaded();

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    /// <summary>
    /// Push current settings to the connected Android device immediately.
    /// The ConnectionManager handles the SETTINGS_SYNC packet.
    /// </summary>
    [RelayCommand]
    private async Task SyncToPhone()
    {
        try
        {
            await _connectionManager.SendSettingsSyncAsync();
            SyncStatusText = "✓  Synced to phone";
            HasSyncStatus  = true;

            // Clear feedback after 3 seconds
            await Task.Delay(3000);
            HasSyncStatus = false;
        }
        catch (Exception ex)
        {
            SyncStatusText = $"✗  Sync failed: {ex.Message}";
            HasSyncStatus  = true;
        }
    }

    /// <summary>
    /// Reset all settings to factory defaults.
    /// </summary>
    [RelayCommand]
    private void ResetToDefaults()
    {
        _settingsRepository.ResetToDefaults();
        _isLoading = true;
        LoadFromSettings(AppSettings.Default);
        _isLoading = false;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void LoadFromSettings(AppSettings settings)
    {
        SensitivityX         = settings.SensitivityX;
        SensitivityY         = settings.SensitivityY;
        Smoothing            = settings.SmoothingFactor;
        DeadZone             = settings.DeadZone;
        StartWithWindows     = settings.StartWithWindows;
        MinimiseToTray       = settings.MinimiseToTray;
        ShowConnectionToasts = settings.ShowConnectionToasts;

        // Initialise display strings
        SensitivityXDisplay  = $"{settings.SensitivityX:F2}x";
        SensitivityYDisplay  = $"{settings.SensitivityY:F2}x";
        SmoothingDisplay   = $"{settings.SmoothingFactor * 100:F0}%";
        DeadZoneDisplay    = $"{settings.DeadZone:F3}";
    }

    private void SaveIfLoaded()
    {
        if (_isLoading) return;

        var settings = _settingsRepository.Current with
        {
            SensitivityX         = SensitivityX,
            SensitivityY         = SensitivityY,
            SmoothingFactor      = Smoothing,
            DeadZone             = DeadZone,
            StartWithWindows     = StartWithWindows,
            MinimiseToTray       = MinimiseToTray,
            ShowConnectionToasts = ShowConnectionToasts
        };

        _settingsRepository.Save(settings);
    }

    private void OnConnectionStateChanged(ConnectionState state)
    {
        CanSync = state == ConnectionState.Connected;
    }
}
