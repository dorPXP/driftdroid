using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;

namespace DriftDroidBuilder.Gui;

public sealed class App : Application
{
    /// <summary>A disc image dropped on the program's icon.</summary>
    public static string? StartDisc { get; set; }

    /// <summary>Set once the window system works; before that a failure falls back to the terminal.</summary>
    public static bool Started { get; private set; }

    public override void Initialize()
    {
        Styles.Add(new FluentTheme());
        RequestedThemeVariant = ThemeVariant.Dark;
    }

    public override void OnFrameworkInitializationCompleted()
    {
        Started = true;
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            I18n.Init();  // the saved language, or the computer's own
            var window = new MainWindow(StartDisc);
            desktop.MainWindow = window;
            // Testing: DRIFTDROID_GUI_SELFTEST=<file.png> opens the window, saves a picture of it
            // (drawn by the program itself) and closes.
            var selfTest = Environment.GetEnvironmentVariable("DRIFTDROID_GUI_SELFTEST");
            if (!string.IsNullOrEmpty(selfTest))
            {
                DispatcherTimer.RunOnce(() =>
                {
                    var size = new PixelSize((int)window.Bounds.Width, (int)window.Bounds.Height);
                    using (var bitmap = new RenderTargetBitmap(size))
                    {
                        bitmap.Render(window);
                        bitmap.Save(selfTest);
                    }
                    desktop.Shutdown();
                }, TimeSpan.FromSeconds(3));
            }
        }
        base.OnFrameworkInitializationCompleted();
    }
}
