using System.Drawing;
using System.Windows.Forms;
using MotionMouse.Connection;
using MotionMouse.Settings;
using Microsoft.Extensions.Logging;
using WpfApplication = System.Windows.Application;

namespace MotionMouse.UI;

/// <summary>
/// System tray icon with context menu.
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private readonly ILogger<TrayIcon>      _logger;
    private readonly ConnectionManager      _connectionManager;
    private readonly SettingsRepository     _settingsRepository;

    private NotifyIcon?        _notifyIcon;
    private ContextMenuStrip?  _contextMenu;

    private ToolStripMenuItem? _statusItem;
    private ToolStripMenuItem? _disconnectItem;
    private ToolStripMenuItem? _startWithWindowsItem;

    public TrayIcon(
        ILogger<TrayIcon> logger,
        ConnectionManager connectionManager,
        SettingsRepository settingsRepository)
    {
        _logger             = logger;
        _connectionManager  = connectionManager;
        _settingsRepository = settingsRepository;
    }

    public void Initialise()
    {
        BuildContextMenu();

        _notifyIcon = new NotifyIcon
        {
            Icon    = SystemIcons.Application,
            Text    = "Motion Mouse \u2014 Not connected",
            Visible = true
        };

        _notifyIcon.DoubleClick      += (_, _) => OpenMainWindow();
        _notifyIcon.ContextMenuStrip  = _contextMenu;

        _connectionManager.StateChanged   += OnConnectionStateChanged;
        _connectionManager.StatusReceived += OnStatusReceived;

        _logger.LogInformation("Tray icon initialised");
    }

    private void BuildContextMenu()
    {
        _contextMenu          = new ContextMenuStrip();
        _contextMenu.Renderer = new DarkMenuRenderer();

        // Header
        var header = new ToolStripMenuItem("Motion Mouse")
        {
            Enabled = false,
            Font    = new Font("Segoe UI", 9f, System.Drawing.FontStyle.Bold)
        };
        _contextMenu.Items.Add(header);
        _contextMenu.Items.Add(new ToolStripSeparator());

        var openItem = new ToolStripMenuItem("Open");
        openItem.Click += (_, _) => OpenMainWindow();
        _contextMenu.Items.Add(openItem);

        var settingsItem = new ToolStripMenuItem("Settings");
        settingsItem.Click += (_, _) => OpenSettingsWindow();
        _contextMenu.Items.Add(settingsItem);

        _contextMenu.Items.Add(new ToolStripSeparator());

        _statusItem = new ToolStripMenuItem("Not connected") { Enabled = false };
        _contextMenu.Items.Add(_statusItem);

        _disconnectItem = new ToolStripMenuItem("Disconnect") { Enabled = false };
        _disconnectItem.Click += async (_, _) => await _connectionManager.DisconnectAsync();
        _contextMenu.Items.Add(_disconnectItem);

        _contextMenu.Items.Add(new ToolStripSeparator());

        var startWithWindows = _settingsRepository.IsStartWithWindowsEnabled();
        _startWithWindowsItem = new ToolStripMenuItem("Start with Windows")
        {
            Checked      = startWithWindows,
            CheckOnClick = true
        };
        _startWithWindowsItem.Click += OnStartWithWindowsToggled;
        _contextMenu.Items.Add(_startWithWindowsItem);

        _contextMenu.Items.Add(new ToolStripSeparator());

        var exitItem = new ToolStripMenuItem("Exit");
        exitItem.Click += (_, _) =>
        {
            if (WpfApplication.Current is App app)
                app.ExitApplication();
        };
        _contextMenu.Items.Add(exitItem);
    }

    private void OnConnectionStateChanged(ConnectionState state)
    {
        switch (state)
        {
            case ConnectionState.Connected:
                var client = _connectionManager.Client;
                var name   = client?.DeviceName ?? "Android Device";
                var via    = client?.TransportName ?? "";
                _notifyIcon!.Icon        = SystemIcons.Information;
                _notifyIcon.Text         = $"Motion Mouse \u2014 {name}";
                _statusItem!.Text        = $"Connected: {name} ({via})";
                _disconnectItem!.Enabled = true;
                break;

            case ConnectionState.Listening:
                _notifyIcon!.Icon        = SystemIcons.Application;
                _notifyIcon.Text         = "Motion Mouse \u2014 Searching\u2026";
                _statusItem!.Text        = "Searching for device\u2026";
                _disconnectItem!.Enabled = false;
                break;

            case ConnectionState.Handshaking:
                _notifyIcon!.Text = "Motion Mouse \u2014 Connecting\u2026";
                _statusItem!.Text = "Connecting\u2026";
                break;

            case ConnectionState.Idle:
                _notifyIcon!.Icon        = SystemIcons.Application;
                _notifyIcon.Text         = "Motion Mouse \u2014 Not connected";
                _statusItem!.Text        = "Not connected";
                _disconnectItem!.Enabled = false;
                break;
        }
    }

    private void OnStatusReceived(int batteryLevel, bool isLocked)
    {
        if (_connectionManager.CurrentState != ConnectionState.Connected) return;
        var client   = _connectionManager.Client;
        var name     = client?.DeviceName ?? "Android";
        var lockText = isLocked ? " [Locked]" : "";
        _notifyIcon!.Text = $"Motion Mouse \u2014 {name} ({batteryLevel}%{lockText})";
    }

    private static void OpenMainWindow()
    {
        if (WpfApplication.Current is App app) app.ShowMainWindow();
    }

    private static void OpenSettingsWindow()
    {
        if (WpfApplication.Current is App app) app.ShowSettingsWindow();
    }

    private void OnStartWithWindowsToggled(object? sender, EventArgs e)
    {
        var settings = _settingsRepository.Current with
        {
            StartWithWindows = _startWithWindowsItem!.Checked
        };
        _settingsRepository.Save(settings);
    }

    public void ShowToast(string title, string message, ToolTipIcon icon = ToolTipIcon.Info)
    {
        if (_settingsRepository.Current.ShowConnectionToasts)
        {
            _notifyIcon?.ShowBalloonTip(3000, title, message, icon);
        }
    }

    public void Dispose()
    {
        _connectionManager.StateChanged   -= OnConnectionStateChanged;
        _connectionManager.StatusReceived -= OnStatusReceived;
        _notifyIcon?.Dispose();
        _contextMenu?.Dispose();
    }
}

internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    private static readonly Color BackgroundColor = Color.FromArgb(19, 19, 26);
    private static readonly Color ForegroundColor = Color.FromArgb(245, 245, 247);
    private static readonly Color HighlightColor  = Color.FromArgb(41, 121, 255);
    private static readonly Color SeparatorColor  = Color.FromArgb(37, 37, 51);
    private static readonly Color DisabledColor   = Color.FromArgb(142, 142, 160);
    private static readonly Color BorderColor     = Color.FromArgb(37, 37, 51);

    public DarkMenuRenderer() : base(new DarkColorTable()) { }

    protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
    {
        var rect = new Rectangle(System.Drawing.Point.Empty, e.Item.Size);
        e.Graphics.FillRectangle(
            new SolidBrush(e.Item.Selected && e.Item.Enabled ? HighlightColor : BackgroundColor),
            rect);
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? ForegroundColor : DisabledColor;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        var rect = e.Item.ContentRectangle;
        var y    = rect.Height / 2;
        e.Graphics.DrawLine(new Pen(SeparatorColor), rect.Left, y, rect.Right, y);
    }

    protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
    {
        var rect = new Rectangle(System.Drawing.Point.Empty, e.ToolStrip.Size);
        rect.Inflate(-1, -1);
        e.Graphics.DrawRectangle(new Pen(BorderColor), rect);
    }
}

internal sealed class DarkColorTable : ProfessionalColorTable
{
    private static readonly Color BgColor     = Color.FromArgb(19, 19, 26);
    private static readonly Color BorderColor = Color.FromArgb(37, 37, 51);

    public override Color MenuItemBorder                => BorderColor;
    public override Color MenuItemSelected              => Color.FromArgb(41, 121, 255);
    public override Color ToolStripDropDownBackground   => BgColor;
    public override Color ImageMarginGradientBegin      => BgColor;
    public override Color ImageMarginGradientMiddle     => BgColor;
    public override Color ImageMarginGradientEnd        => BgColor;
    public override Color MenuBorder                    => BorderColor;
    public override Color MenuItemSelectedGradientBegin => Color.FromArgb(41, 121, 255);
    public override Color MenuItemSelectedGradientEnd   => Color.FromArgb(41, 121, 255);
}
