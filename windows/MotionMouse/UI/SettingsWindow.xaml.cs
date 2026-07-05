using System.Diagnostics;
using System.Windows;
using System.Windows.Navigation;
using MotionMouse.UI.ViewModels;

namespace MotionMouse.UI;

/// <summary>
/// Code-behind for SettingsWindow.
/// Minimal — all logic in SettingsViewModel.
/// </summary>
public partial class SettingsWindow : Window
{
    private readonly SettingsViewModel _viewModel;

    public SettingsWindow(SettingsViewModel viewModel)
    {
        InitializeComponent();
        _viewModel  = viewModel;
        DataContext = viewModel;
    }

    private void TitleBar_MouseLeftButtonDown(object sender,
        System.Windows.Input.MouseButtonEventArgs e)
    {
        DragMove();
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    /// <summary>
    /// Opens hyperlinks in the system default browser.
    /// </summary>
    private void Hyperlink_RequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
        e.Handled = true;
    }
}
