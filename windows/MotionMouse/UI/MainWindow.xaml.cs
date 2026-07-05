using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Brush = System.Windows.Media.Brush;
using Color = System.Windows.Media.Color;
using MotionMouse.UI.ViewModels;

namespace MotionMouse.UI;

/// <summary>
/// Code-behind for MainWindow.
///
/// Kept intentionally minimal — all logic lives in MainViewModel.
/// Code-behind handles only things that are genuinely view-specific:
///   - Window dragging (custom title bar)
///   - Pulse animation (purely visual, no state)
///   - Close-to-tray behaviour
///   - ViewModel wiring
/// </summary>
public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        _viewModel   = viewModel;
        DataContext  = viewModel;

        Loaded  += OnLoaded;
        Closing += OnClosing;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        StartPulseAnimation();

        // Update status dot colour based on connection state
        _viewModel.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is nameof(MainViewModel.IsConnected)
                                  or nameof(MainViewModel.IsListening))
            {
                UpdateStatusDot();
            }
        };

        UpdateStatusDot();
    }

    /// <summary>
    /// Close to tray instead of exiting.
    ///
    /// When the user clicks the X button, we hide the window
    /// rather than closing it. The app keeps running in the tray.
    /// The user can reopen via tray double-click or "Open" menu item.
    ///
    /// The only way to truly exit is via the tray menu "Exit" item,
    /// which calls App.ExitApplication() and calls Shutdown().
    /// </summary>
    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        e.Cancel = true; // Suppress close
        Hide();          // Hide instead
    }

    /// <summary>
    /// Allow dragging the window by clicking the custom title bar area.
    /// Called from XAML MouseLeftButtonDown on the title bar Grid.
    /// </summary>
    private void TitleBar_MouseLeftButtonDown(object sender,
        System.Windows.Input.MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2)
        {
            // Double-click title bar — toggle maximise (disabled, just normalise)
            WindowState = WindowState.Normal;
        }
        else
        {
            DragMove();
        }
    }

    /// <summary>
    /// Update the small status dot in the title bar.
    ///
    ///   Green  → connected
    ///   Amber  → listening / searching
    ///   Grey   → idle / disconnected
    /// </summary>
    private void UpdateStatusDot()
    {
        var brush = _viewModel.IsConnected
            ? (SolidColorBrush)FindResource("ConnectedBrush")
            : _viewModel.IsListening
                ? (SolidColorBrush)FindResource("SearchingBrush")
                : (SolidColorBrush)FindResource("DisconnectedBrush");

        StatusDot.Fill = brush;
    }

    /// <summary>
    /// Animate the pulse ring behind the search icon.
    ///
    /// Two animations run in parallel on a loop:
    ///   Scale: 0.8 → 1.4 (ring expands outward)
    ///   Opacity: 0.6 → 0 (ring fades as it expands)
    ///
    /// This creates the "sonar pulse" effect that communicates
    /// active scanning without being distracting.
    /// The animation automatically stops when the window is hidden.
    /// </summary>
    private void StartPulseAnimation()
    {
        var duration = new Duration(TimeSpan.FromSeconds(2));

        // Scale animation
        var scaleAnim = new DoubleAnimation
        {
            From           = 0.8,
            To             = 1.4,
            Duration       = duration,
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };

        // Opacity animation
        var opacityAnim = new DoubleAnimation
        {
            From           = 0.6,
            To             = 0.0,
            Duration       = duration,
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };

        PulseRing.BeginAnimation(OpacityProperty, opacityAnim);
        PulseScale.BeginAnimation(ScaleTransform.ScaleXProperty, scaleAnim);
        PulseScale.BeginAnimation(ScaleTransform.ScaleYProperty, scaleAnim);
    }
}
