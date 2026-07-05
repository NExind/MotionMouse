using System.Windows;
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
}
