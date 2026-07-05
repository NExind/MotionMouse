using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using MotionMouse.Connection;
using MotionMouse.Connection.Bluetooth;
using MotionMouse.Connection.WiFi;
using MotionMouse.Cursor;
using MotionMouse.Protocol;
using MotionMouse.Settings;
using MotionMouse.UI;
using MotionMouse.UI.ViewModels;
using System.Windows;
using Application = System.Windows.Application;
using Window = System.Windows.Window;

namespace MotionMouse;

/// <summary>
/// Application entry point and DI container host.
///
/// Responsibilities:
///   - Parse command-line arguments (--minimised for startup-with-Windows)
///   - Build the DI service container
///   - Start the connection manager
///   - Create the system tray icon
///   - Show or hide the main window on launch
///   - Handle clean shutdown
///
/// Architecture note:
///   We use Microsoft.Extensions.DependencyInjection — the same
///   container abstraction used on Android (via Hilt).
///   All singletons are registered here and injected throughout.
///   No static globals, no service locator pattern.
///
/// WPF + DI:
///   WPF does not have built-in DI support like ASP.NET Core.
///   We handle this by resolving the main window and ViewModels
///   from the container explicitly in OnStartup().
///   All other types are constructor-injected normally.
/// </summary>
public partial class App : Application
{
    private ServiceProvider?    _serviceProvider;
    private TrayIcon?           _trayIcon;
    private bool                _startMinimised;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Check for --minimised flag (set by StartWithWindows registry entry)
        _startMinimised = e.Args.Contains("--minimised");

        // Build DI container
        _serviceProvider = BuildServiceProvider();

        // Load settings from disk
        var settingsRepo = _serviceProvider.GetRequiredService<SettingsRepository>();
        settingsRepo.Load();

        // Create and show tray icon
        _trayIcon = _serviceProvider.GetRequiredService<TrayIcon>();
        _trayIcon.Initialise();

        // Start listening for Android connections
        var connectionManager = _serviceProvider.GetRequiredService<ConnectionManager>();
        connectionManager.StartListening();

        // Show main window unless started minimised
        if (!_startMinimised)
        {
            ShowMainWindow();
        }
    }

    /// <summary>
    /// Build the DI service container.
    ///
    /// Registration order:
    ///   1. Infrastructure (logging)
    ///   2. Settings
    ///   3. Protocol
    ///   4. Cursor
    ///   5. Connection (depends on cursor + protocol + settings)
    ///   6. UI (depends on connection + settings)
    ///
    /// All core services are Singleton — one instance for the app lifetime.
    /// ViewModels are Transient — created fresh for each window.
    /// </summary>
    private static ServiceProvider BuildServiceProvider()
    {
        var services = new ServiceCollection();

        // --- Logging ---
        services.AddLogging(builder =>
        {
            builder.SetMinimumLevel(LogLevel.Debug);
            builder.AddDebug();
            builder.AddConsole();
            builder.AddProvider(new FileLoggerProvider());
            builder.SetMinimumLevel(LogLevel.Debug);
        });

        // --- Settings ---
        services.AddSingleton<SettingsRepository>();

        // --- Protocol ---
        services.AddSingleton<PacketParser>();
        // PacketBuilder is static — no registration needed

        // --- Cursor ---
        services.AddSingleton<CursorController>();

        // --- Connection ---
        services.AddSingleton<UdpDiscoveryServer>();
        services.AddSingleton<WiFiTransport>();
        services.AddSingleton<BluetoothTransport>();
        services.AddSingleton<ConnectionManager>();

        // --- UI ---
        services.AddSingleton<TrayIcon>();
        services.AddTransient<MainWindow>();
        services.AddTransient<MainViewModel>();
        services.AddTransient<SettingsWindow>();
        services.AddTransient<SettingsViewModel>();

        return services.BuildServiceProvider();
    }

    /// <summary>
    /// Show (or bring to front) the main window.
    /// Called from tray icon "Open" menu item.
    /// </summary>
    public void ShowMainWindow()
    {
        if (_serviceProvider is null) return;

        var existing = Windows.OfType<MainWindow>().FirstOrDefault();
        if (existing is not null)
        {
            existing.Show();
            existing.WindowState = WindowState.Normal;
            existing.Activate();
            return;
        }

        var window = _serviceProvider.GetRequiredService<MainWindow>();
        window.Show();
    }

    /// <summary>
    /// Show (or bring to front) the settings window.
    /// Called from tray icon "Settings" menu item.
    /// </summary>
    public void ShowSettingsWindow()
    {
        if (_serviceProvider is null) return;

        var existing = Windows.OfType<SettingsWindow>().FirstOrDefault();
        if (existing is not null)
        {
            existing.Activate();
            return;
        }

        var window = _serviceProvider.GetRequiredService<SettingsWindow>();
        window.Show();
    }

    /// <summary>
    /// Cleanly shut down the application.
    /// Called from tray icon "Exit" menu item.
    /// </summary>
    public async void ExitApplication()
    {
        try
        {
            // Disconnect gracefully
            var connectionManager =
                _serviceProvider?.GetRequiredService<ConnectionManager>();
            if (connectionManager is not null)
                await connectionManager.DisconnectAsync();
        }
        catch { /* Non-fatal on exit */ }
        finally
        {
            _trayIcon?.Dispose();
            _serviceProvider?.Dispose();
            Shutdown();
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _trayIcon?.Dispose();
        _serviceProvider?.Dispose();
        base.OnExit(e);
    }
}
