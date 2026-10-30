using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;

namespace DriftDroidBuilder.Gui;

/// <summary>
/// The builder's window, made to look like the DriftDroid launchers on Android and Switch: the
/// drifting checkerboard, the logo, rounded grey panels and one blue button for the next step.
/// Everything is laid out in the Switch launcher's 1280x720 units and scaled to the window.
///
/// Three screens: choose the disc and what to make, the build's checklist, and the result with
/// what to do with the files.
/// </summary>
public sealed class MainWindow : Window
{
    private const double W = 1280;
    private const double H = 720;

    private readonly Checkerboard _background = new();
    private readonly Canvas _page = new() { Width = W, Height = H };
    private readonly Grid _overlay = new() { Width = W, Height = H, IsVisible = false };
    private readonly DispatcherTimer _frame;

    // Setup choices.
    private string? _disc;
    private Builder.DiscCheck? _discCheck;
    private bool _checkingDisc;
    private bool _android = true;
    private bool _switch;
    private bool _retroRewind = true;
    private bool _switchReady;
    private string? _installProblem;
    private bool _onHome;

    // A running build.
    private bool _building;
    private bool _closeAfterStop;
    private readonly object _gate = new();
    private readonly StringBuilder _pendingLog = new();
    private string _progressText = "";
    private double? _progressValue;
    private Stage? _pendingStage;
    private readonly Stopwatch _elapsed = new();
    private readonly List<(Stage Stage, StepMark Mark, TextBlock Label, TextBlock Sub)> _steps = new();
    private readonly Dictionary<Stage, DateTime> _stageStart = new();
    private Stage? _currentStage;
    private TextBlock? _nowTitle;
    private TextBlock? _nowText;
    private TextBlock? _elapsedText;
    private Bar? _bar;
    private TextBox? _logBox;
    private Border? _logPanel;
    private readonly StringBuilder _logText = new();

    public MainWindow(string? disc)
    {
        Title = "DriftDroid Builder";
        Width = 1120;
        Height = 660;
        MinWidth = 720;
        MinHeight = 420;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new SolidColorBrush(Palette.CheckDark);
        Icon = new WindowIcon(typeof(MainWindow).Assembly.GetManifestResourceStream("icon.png")!);

        var stage = new Grid { Width = W, Height = H };
        stage.Children.Add(_page);
        stage.Children.Add(_overlay);
        var root = new Grid();
        root.Children.Add(_background);
        root.Children.Add(new Viewbox { Stretch = Stretch.Uniform, Child = stage });
        Content = root;

        DragDrop.SetAllowDrop(this, true);
        AddHandler(DragDrop.DragOverEvent, (_, e) => e.DragEffects = _onHome && !_overlay.IsVisible ? DragDropEffects.Copy : DragDropEffects.None);
        AddHandler(DragDrop.DropEvent, OnDrop);
        Closing += OnClosing;

        _frame = new DispatcherTimer(TimeSpan.FromMilliseconds(33), DispatcherPriority.Render, (_, _) => OnFrame());
        _frame.Start();

        try
        {
            Builder.CheckInstall();
        }
        catch (BuildException e)
        {
            _installProblem = e.Message;
        }
        _switchReady = Builder.SwitchCompilerInstalled();
        ShowHome();
        if (disc != null)
        {
            ChooseDisc(disc);
        }
    }

    // --- Screen 1: the disc and what to make ------------------------------------------------------

    private void ShowHome()
    {
        _onHome = true;
        _page.Children.Clear();

        var logo = new Image { Source = Ui.Art("logo.png"), Stretch = Stretch.Uniform };
        logo.Place(_page, (W - 500) / 2, 22, 500, 93);
        var tagline = Ui.Label($"Builder {Builder.Version}  ·  " + I18n.T("makes DriftDroid's game files from your own Mario Kart Wii disc"), 21,
            Palette.Muted);
        tagline.TextAlignment = TextAlignment.Center;
        tagline.Place(_page, 0, 122, W);

        const double left = 140;
        const double width = W - 2 * left;
        SectionHeading(1, "Your Mario Kart Wii disc", left, 168);
        var disc = DiscTile();
        disc.Place(_page, left, 210, width, 100);
        disc.Click += PickDiscAsync;

        SectionHeading(2, "What do you want to make?", left, 330);
        var half = (width - 20) / 2;
        var android = TargetTile(Icons.Phone(76), "Android phone", "A game pack for the DriftDroid app", null, _android);
        android.Place(_page, left, 372, half, 118);
        android.Click += () =>
        {
            _android = !_android;
            ShowHome();
        };
        var nswitch = TargetTile(Icons.Switch(54), "Nintendo Switch", "DriftDroid.nro and RetroRewind.nro",
            _switchReady ? null : "Needs devkitPro first (free). Click to see how.", _switch);
        nswitch.Place(_page, left + half + 20, 372, half, 118);
        nswitch.Click += () =>
        {
            if (!_switch && !(_switchReady = Builder.SwitchCompilerInstalled()))
            {
                ShowDevkitProHelp();
                return;
            }
            _switch = !_switch;
            ShowHome();
        };

        var retro = RetroRewindTile();
        retro.Place(_page, left, 504, width, 58);
        retro.Click += () =>
        {
            _retroRewind = !_retroRewind;
            ShowHome();
        };

        // What is still missing, or what to expect.
        var needed = Builder.NeededGb(_android, _switch);
        var free = Builder.FreeGb();
        var spaceOk = free >= needed || Builder.SkipSpaceCheck;
        string? blocker = _installProblem
                          ?? (_disc == null ? "Choose your disc image first."
                              : _checkingDisc ? "Checking the disc..."
                              : _discCheck is { Ok: false } ? "This disc can't be used: choose another one."
                              : !_android && !_switch ? "Choose Android, Switch or both."
                              : !spaceOk ? $"Not enough disk space: it needs about {needed} GB and this drive has {free} GB free."
                              : null);
        var info = new StackPanel { Spacing = 4 };
        if (blocker != null)
        {
            var serious = _installProblem != null || blocker.StartsWith("Not enough", StringComparison.Ordinal);
            info.Children.Add(Ui.Label(blocker, 20, serious ? Palette.Bad : Palette.Warn,
                FontWeight.SemiBold, 610));
        }
        if (spaceOk)
        {
            info.Children.Add(Ui.Label($"Takes 30 minutes to 2 hours. Uses about {needed} GB of disk space ({free} GB free).", 17,
                Palette.Muted, FontWeight.Normal, 610));
        }
        info.Place(_page, left, 580);

        var start = Tile.Text(Tone.Primary, "Start", 32, 18);
        start.IsEnabled = blocker == null;
        start.Place(_page, left + width - 360, 576, 360, 62);
        start.Click += StartBuild;

        Footer("Nothing is uploaded. Everything happens on this computer.",
            ("Help", Tone.Grey, () => Ui.Open(Path.Combine(Builder.BaseDir, "README.txt"))),
            ("Language", Tone.Grey, ShowLanguageDialog),
            ("Output folder", Tone.Grey, () =>
            {
                Directory.CreateDirectory(Builder.OutputDir);
                Ui.Reveal(Builder.OutputDir);
            }));
    }

    private void SectionHeading(int number, string text, double x, double y)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        row.Children.Add(Icons.Number(number, 34));
        var label = Ui.Label(text, 26, Palette.Text, FontWeight.SemiBold);
        label.VerticalAlignment = VerticalAlignment.Center;
        row.Children.Add(label);
        row.Place(_page, x, y);
    }

    private Tile DiscTile()
    {
        string title, detail, action;
        IBrush detailColor = Palette.Muted;
        if (_disc == null)
        {
            title = "Choose your disc image...";
            detail = "A PAL .iso or .wbfs file (game ID RMCP01). You can also drag it onto this window.";
            action = "Browse";
        }
        else if (_checkingDisc || _discCheck == null)
        {
            title = "Checking the disc...";
            detail = Path.GetFileName(_disc);
            action = "";
        }
        else
        {
            title = _discCheck.Title;
            detail = _discCheck.Ok ? _disc : _discCheck.Detail;
            detailColor = _discCheck.Ok ? Palette.Muted : Palette.Bad;
            action = "Change";
        }
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Margin = new Thickness(24, 0) };
        var icon = Icons.Disc(64);
        icon.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(icon);
        var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(22, 0), Spacing = 4 };
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        if (_discCheck is { Ok: true } && !_checkingDisc)
        {
            titleRow.Children.Add(Icons.Check(28));
        }
        titleRow.Children.Add(Ui.Label(title, 27, Palette.Text, FontWeight.SemiBold));
        texts.Children.Add(titleRow);
        var sub = Ui.Label(detail, 18, detailColor);
        // A long path keeps its end (the file name) visible.
        sub.TextTrimming = _discCheck is { Ok: true } ? TextTrimming.PrefixCharacterEllipsis : TextTrimming.None;
        sub.TextWrapping = _discCheck is { Ok: true } ? TextWrapping.NoWrap : TextWrapping.Wrap;
        texts.Children.Add(sub);
        Grid.SetColumn(texts, 1);
        grid.Children.Add(texts);
        var actionLabel = Ui.Label(action, 21, Palette.AccentHi, FontWeight.SemiBold);
        actionLabel.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(actionLabel, 2);
        grid.Children.Add(actionLabel);
        var tile = new Tile(Tone.Normal, grid, 22) { IsEnabled = _installProblem == null };
        if (_discCheck is { Ok: true } && !_checkingDisc)
        {
            tile.Selected = true;
        }
        return tile;
    }

    private Tile TargetTile(Control icon, string title, string detail, string? warning, bool chosen)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("110,*"), Margin = new Thickness(18, 0, 18, 0) };
        icon.HorizontalAlignment = HorizontalAlignment.Center;
        icon.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(icon);
        var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 0, 0), Spacing = 6 };
        texts.Children.Add(Ui.Label(title, 28, Palette.Text, FontWeight.SemiBold));
        texts.Children.Add(Ui.Label(warning ?? detail, 18, warning != null ? Palette.Warn : Palette.Muted, FontWeight.Normal, 320));
        Grid.SetColumn(texts, 1);
        grid.Children.Add(texts);
        var check = Icons.Check(34);
        check.HorizontalAlignment = HorizontalAlignment.Right;
        check.VerticalAlignment = VerticalAlignment.Top;
        check.Margin = new Thickness(0, 12, -6, 0);
        check.IsVisible = chosen;
        Grid.SetColumn(check, 1);
        grid.Children.Add(check);
        return new Tile(Tone.Normal, grid, 22) { Selected = chosen, IsEnabled = _installProblem == null };
    }

    /// <summary>The Retro Rewind tick: a check box, the channel banner, and what leaving it out means.</summary>
    private Tile RetroRewindTile()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, Margin = new Thickness(18, 0),
            VerticalAlignment = VerticalAlignment.Center };
        var box = new Border
        {
            Width = 30,
            Height = 30,
            CornerRadius = new CornerRadius(7),
            BorderThickness = new Thickness(2),
            BorderBrush = _retroRewind ? Palette.AccentHi : Palette.Border,
            Background = _retroRewind ? Palette.Accent : Palette.Solid(0x22, 0x22, 0x22),
            VerticalAlignment = VerticalAlignment.Center,
        };
        if (_retroRewind)
        {
            box.Child = new Avalonia.Controls.Shapes.Path
            {
                Data = Geometry.Parse("M 6,14 L 11.5,19.5 L 21,8"),
                Stroke = Palette.White,
                StrokeThickness = 3.5,
                StrokeLineCap = PenLineCap.Round,
                StrokeJoin = PenLineJoin.Round,
            };
        }
        row.Children.Add(box);
        var banner = new Border
        {
            CornerRadius = new CornerRadius(8),
            ClipToBounds = true,
            Width = 166,
            Height = 38,
            Child = new Image { Source = Ui.Art("channel_retro_rewind.jpg"), Stretch = Stretch.UniformToFill },
            Opacity = _retroRewind ? 1 : 0.45,
            VerticalAlignment = VerticalAlignment.Center,
        };
        row.Children.Add(banner);
        var label = Ui.Label("Include Retro Rewind", 23, Palette.Text, FontWeight.SemiBold);
        label.VerticalAlignment = VerticalAlignment.Center;
        row.Children.Add(label);
        var note = Ui.Label(_retroRewind
                ? "The custom-track mod, a second game in DriftDroid. Untick for a quicker build."
                : "Left out: quicker build, but no Retro Rewind until you build again with it.",
            17, _retroRewind ? Palette.Muted : Palette.Warn);
        note.VerticalAlignment = VerticalAlignment.Center;
        note.TextWrapping = TextWrapping.Wrap;
        note.Width = 400;
        row.Children.Add(note);
        return new Tile(Tone.Normal, row, 16) { IsEnabled = _installProblem == null };
    }

    private async void PickDiscAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Choose your Mario Kart Wii disc image",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType("Wii disc image (.iso, .wbfs)") { Patterns = new[] { "*.iso", "*.wbfs", "*.ISO", "*.WBFS" } },
                FilePickerFileTypes.All,
            },
        });
        var path = files.FirstOrDefault()?.TryGetLocalPath();
        if (path != null)
        {
            ChooseDisc(path);
        }
    }

    private void OnDrop(object? sender, DragEventArgs e)
    {
        if (!_onHome || _overlay.IsVisible)
        {
            return;
        }
        var path = e.Data.GetFiles()?.Select(f => f.TryGetLocalPath()).FirstOrDefault(p => p != null && File.Exists(p));
        if (path != null)
        {
            ChooseDisc(path);
        }
    }

    private async void ChooseDisc(string path)
    {
        _disc = path;
        _discCheck = null;
        _checkingDisc = true;
        ShowHome();
        var check = await Task.Run(() => Builder.CheckDisc(path));
        if (_disc != path)
        {
            return; // another disc was chosen meanwhile
        }
        _discCheck = check;
        _checkingDisc = false;
        if (_onHome)
        {
            ShowHome();
        }
    }

    private void ShowDevkitProHelp()
    {
        var steps = OperatingSystem.IsWindows()
            ? I18n.T("1. Open the devkitPro page below and download the Windows installer.\n") +
              I18n.T("2. Run it and tick \"Switch Development\".\n") +
              I18n.T("3. Come back here and press \"Check again\".")
            : I18n.T("1. Open the devkitPro page below and follow its steps for your Linux.\n") +
              I18n.T("2. Install the \"switch-dev\" package with dkp-pacman.\n") +
              I18n.T("3. Come back here and press \"Check again\".");
        ShowDialog("Switch needs devkitPro",
            I18n.T("devkitPro is the free compiler for Switch homebrew. Its makers ask that it's installed with " +
                   "their own installer, so the builder can't download it for you.") + "\n\n" + steps,
            ("Open devkitPro page", Tone.Primary, false, () => Ui.Open("https://devkitpro.org/wiki/Getting_Started")),
            ("Check again", Tone.Normal, false, () =>
            {
                if (_switchReady = Builder.SwitchCompilerInstalled())
                {
                    _switch = true;
                    CloseDialog();
                    ShowHome();
                }
                else
                {
                    ShowDevkitProHelp();
                    DialogNote(I18n.T("Still not found. If you just installed it, restart the computer and try again."));
                }
            }),
            ("Cancel", Tone.Grey, true, () => { }));
    }

    // --- Screen 2: the build ----------------------------------------------------------------------

    private async void StartBuild()
    {
        if (_disc == null || _building)
        {
            return;
        }
        _building = true;
        _onHome = false;
        Cancel.Reset();
        Log.OpenFile(Builder.LogPath);
        Log.Line += OnLogLine;
        Log.StageStarted += OnStage;
        Log.ProgressChanged += OnProgress;
        ShowBuilding();
        KeepAwake(true);
        var request = new BuildRequest(_disc, _android, _switch, _retroRewind, null);
        try
        {
            var result = await Task.Run(() => Builder.RunAsync(request));
            FlushBuildState();
            ShowDone(result);
        }
        catch (Exception e)
        {
            FlushBuildState();
            if (Cancel.Token.IsCancellationRequested)
            {
                Log.Info("\nStopped by the player.");
                ShowFailed(null);
            }
            else if (e is BuildException)
            {
                Log.Info($"\nStopped: {e.Message}");
                ShowFailed(e.Message);
            }
            else
            {
                Log.Info($"\nSomething went wrong: {e}");
                ShowFailed($"Something went wrong inside the builder:\n{e.Message}\n\n" +
                           "Please report it on DriftDroid's GitHub page with builder-log.txt attached.");
            }
        }
        finally
        {
            Log.Line -= OnLogLine;
            Log.StageStarted -= OnStage;
            Log.ProgressChanged -= OnProgress;
            KeepAwake(false);
            _building = false;
            _elapsed.Stop();
            if (_closeAfterStop)
            {
                Close();
            }
        }
    }

    private void ShowBuilding()
    {
        _page.Children.Clear();
        _steps.Clear();
        _stageStart.Clear();
        _currentStage = null;
        _logText.Clear();
        lock (_gate)
        {
            _pendingLog.Clear();
            _pendingStage = null;
            _progressText = I18n.T("Starting...");
            _progressValue = null;
        }
        _elapsed.Restart();

        Header("Making your game files", "This takes a while. You can keep using your computer; leave this window open.",
            Palette.Accent);

        var stages = new List<(Stage, string)>
        {
            (Stage.Prepare, "Getting ready"),
            (Stage.Disc, "Reading your disc"),
            (Stage.RetroRewind, "Getting Retro Rewind's game code"),
            (Stage.Translate, "Translating the game code"),
        };
        if (_android)
        {
            stages.Add((Stage.Android, "Building for Android"));
        }
        if (_switch)
        {
            stages.Add((Stage.Switch, "Building for Switch"));
        }
        var y = 162.0;
        foreach (var (stage, label) in stages)
        {
            var mark = new StepMark { Width = 36, Height = 36 };
            mark.Place(_page, 64, y);
            var name = Ui.Label(label, 25, Palette.Text, FontWeight.SemiBold).Place(_page, 118, y - 2);
            var sub = Ui.Label("", 17, Palette.Muted).Place(_page, 118, y + 30);
            _steps.Add((stage, mark, name, sub));
            y += 72;
        }

        var card = new Border
        {
            Background = Palette.Panel,
            BorderBrush = Palette.Border,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(22),
        };
        card.Place(_page, 620, 160, 596, 300);
        Ui.Label("NOW", 17, Palette.Muted, FontWeight.Bold).Place(_page, 656, 188);
        _nowTitle = Ui.Label("Getting ready", 30, Palette.Text, FontWeight.SemiBold, 524).Place(_page, 656, 214);
        _nowText = Ui.Label("", 21, Palette.Muted, FontWeight.Normal, 524).Place(_page, 656, 262);
        _bar = new Bar().Place(_page, 656, 360, 524, 22);
        _elapsedText = Ui.Label("", 19, Palette.Muted).Place(_page, 656, 404);

        _logBox = new TextBox
        {
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.NoWrap,
            FontFamily = new FontFamily("Consolas, DejaVu Sans Mono, Liberation Mono, monospace"),
            FontSize = 15,
            Background = Palette.Solid(0x16, 0x16, 0x16),
            Foreground = Palette.Text,
            BorderThickness = new Thickness(0),
        };
        _logPanel = new Border
        {
            Background = Palette.Solid(0x16, 0x16, 0x16),
            BorderBrush = Palette.Border,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Padding = new Thickness(10),
            ClipToBounds = true,
            Child = _logBox,
            IsVisible = false,
        };
        _logPanel.Place(_page, 64, 150, W - 128, 486);

        Tile? details = null;
        details = FooterButton("Show details", Tone.Grey, 64, 240, () =>
        {
            _logPanel.IsVisible = !_logPanel.IsVisible;
            ((TextBlock)details!.Child!).Text = _logPanel.IsVisible ? "Hide details" : "Show details";
            if (_logPanel.IsVisible)
            {
                _logBox.Text = _logText.ToString();
                _logBox.CaretIndex = _logBox.Text.Length;
            }
        });
        FooterButton("Stop", Tone.Danger, W - 64 - 220, 220, ConfirmStop);
        FooterLine();
    }

    private void ConfirmStop()
    {
        ShowDialog("Stop the build?",
            "Everything made so far is thrown away and the next build starts over. Downloads are kept.",
            ("Keep going", Tone.Primary, true, () => { }),
            ("Stop", Tone.Danger, true, () => Cancel.Request()));
    }

    // Called from the build's threads.
    private void OnLogLine(string text, bool heading)
    {
        lock (_gate)
        {
            _pendingLog.Append(heading ? $"\n== {text}\n" : text + "\n");
        }
    }

    private void OnStage(Stage stage)
    {
        lock (_gate)
        {
            _pendingStage = stage;
            _progressText = "";
            _progressValue = null;
        }
    }

    private void OnProgress(string text, double? value)
    {
        lock (_gate)
        {
            _progressText = text;
            _progressValue = value;
        }
    }

    /// <summary>Takes over what the build reported since the last frame.</summary>
    private void FlushBuildState()
    {
        string log, text;
        double? value;
        Stage? stage;
        lock (_gate)
        {
            log = _pendingLog.ToString();
            _pendingLog.Clear();
            stage = _pendingStage;
            _pendingStage = null;
            text = _progressText;
            value = _progressValue;
        }
        if (stage is { } s)
        {
            EnterStage(s);
        }
        if (_nowText != null)
        {
            _nowText.Text = text;
        }
        if (_bar != null)
        {
            _bar.Value = value;
        }
        if (log.Length > 0)
        {
            _logText.Append(log);
            // The details view keeps the last part only; the whole log is in builder-log.txt.
            if (_logText.Length > 400_000)
            {
                _logText.Remove(0, _logText.Length - 300_000);
            }
            if (_logBox != null && _logPanel is { IsVisible: true })
            {
                _logBox.Text = _logText.ToString();
                _logBox.CaretIndex = _logBox.Text.Length;
            }
        }
    }

    private void EnterStage(Stage stage)
    {
        var now = DateTime.UtcNow;
        foreach (var (s, mark, name, sub) in _steps)
        {
            if (s == _currentStage)
            {
                mark.State = StepState.Done;
                sub.Text = $"Done in {Duration(now - _stageStart[s])}";
            }
            if (s == stage)
            {
                mark.State = StepState.Working;
                sub.Text = I18n.T("Working on it...");
                if (_nowTitle != null)
                {
                    _nowTitle.Text = name.Text;
                }
            }
        }
        _stageStart[stage] = now;
        _currentStage = stage;
    }

    private void OnFrame()
    {
        _background.InvalidateVisual();
        if (!_building)
        {
            return;
        }
        FlushBuildState();
        _bar?.InvalidateVisual();
        foreach (var (_, mark, _, _) in _steps)
        {
            if (mark.State == StepState.Working)
            {
                mark.InvalidateVisual();
            }
        }
        if (_elapsedText != null)
        {
            _elapsedText.Text = $"Running for {_elapsed.Elapsed:h\\:mm\\:ss}";
        }
    }

    // --- Screen 3: the result ---------------------------------------------------------------------

    private void ShowDone(BuildResult result)
    {
        _page.Children.Clear();
        var retro = result.SwitchFiles.Any(f => Path.GetFileName(f) == "RetroRewind.nro") ||
                    (result.AndroidPack != null && _retroRewind);
        Header("All done!", retro
                ? $"Finished in {Duration(_elapsed.Elapsed)}  ·  made for Retro Rewind {result.RetroRewind.Version}"
                : $"Finished in {Duration(_elapsed.Elapsed)}  ·  without Retro Rewind",
            Palette.Good);

        var cards = new List<(Control Icon, string Title, string Steps, string Button, string Target)>();
        if (result.AndroidPack is { } pack)
        {
            cards.Add((Icons.Phone(70), "Android phone",
                string.Format(I18n.T("1.  Copy {0} to your phone (USB cable, cloud drive, whatever you like).\n"), Path.GetFileName(pack)) +
                I18n.T("2.  Open DriftDroid on the phone and tap \"Import game pack\".\n") +
                I18n.T("3.  Pick the file. That's it."),
                "Show the file", pack));
        }
        if (result.SwitchFiles.Count > 0)
        {
            var withRetro = result.SwitchFiles.Count > 1;
            cards.Add((Icons.Switch(50), "Nintendo Switch",
                I18n.T("1.  Put the SD card in your computer (or use DBI's USB mode).\n") +
                I18n.T("2.  Copy DriftDroid.nro into the /switch/ folder.\n") +
                (withRetro
                    ? I18n.T("3.  Copy RetroRewind.nro into /switch/WiiCompiled/ (it starts from DriftDroid's Retro Rewind button).\n") +
                      I18n.T("4.  Start DriftDroid from the Homebrew Menu.")
                    : I18n.T("3.  Start DriftDroid from the Homebrew Menu.")),
                "Show the files", result.SwitchFiles[0]));
        }
        var cardWidth = cards.Count == 1 ? 760.0 : (W - 128 - 24) / 2;
        var x = cards.Count == 1 ? (W - cardWidth) / 2 : 64;
        foreach (var (icon, title, steps, button, target) in cards)
        {
            new Border
            {
                Background = Palette.Panel,
                BorderBrush = Palette.Border,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(22),
            }.Place(_page, x, 160, cardWidth, 452);
            var head = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 18 };
            icon.VerticalAlignment = VerticalAlignment.Center;
            head.Children.Add(icon);
            var name = Ui.Label(title, 30, Palette.Text, FontWeight.SemiBold);
            name.VerticalAlignment = VerticalAlignment.Center;
            head.Children.Add(name);
            head.Place(_page, x + 36, 190);
            var text = Ui.Label(steps, 21, Palette.Text, FontWeight.Normal, cardWidth - 72);
            text.LineHeight = 34;
            text.Place(_page, x + 36, 296);
            var show = Tile.Text(Tone.Primary, button, 24);
            show.Place(_page, x + 36, 520, 300, 66);
            show.Click += () => Ui.Reveal(target);
            x += cardWidth + 24;
        }

        var bytes = Builder.WorkFolderBytes();
        Tile? free = null;
        free = FooterButton(bytes > 0 ? $"Free up {bytes / (1L << 30)} GB" : "Work folder deleted", Tone.Grey, 64, 280, () =>
        {
            if (Builder.WorkFolderBytes() == 0)
            {
                return;
            }
            ShowDialog("Delete the work folder?",
                "It holds the translated code and the compiler's in-between files. Your finished files in " +
                "the output folder are kept. A new build makes the work folder again.",
                ("Delete it", Tone.Primary, true, () =>
                {
                    try
                    {
                        Directory.Delete(Builder.WorkDir, recursive: true);
                    }
                    catch (IOException)
                    {
                    }
                    ((TextBlock)free!.Child!).Text = I18n.T("Work folder deleted");
                }),
                ("Cancel", Tone.Grey, true, () => { }));
        });
        FooterButton("Back to start", Tone.Grey, 364, 240, ShowHome);
        FooterButton("Close", Tone.Primary, W - 64 - 220, 220, Close);
        FooterLine();
    }

    private void ShowFailed(string? message)
    {
        var during = _steps.FirstOrDefault(s => s.Stage == _currentStage).Label?.Text;
        _page.Children.Clear();
        if (message == null)
        {
            Header("Stopped", "You stopped the build. Start it again whenever you like.", Palette.Warn);
        }
        else
        {
            Header("The build stopped", during != null ? $"It stopped at \"{during}\". Here is what happened:" : "Here is what happened:",
                Palette.Bad);
            var box = new TextBox
            {
                Text = message,
                IsReadOnly = true,
                AcceptsReturn = true,
                TextWrapping = TextWrapping.Wrap,
                FontSize = 19,
                Background = Palette.Panel,
                Foreground = Palette.Text,
                BorderThickness = new Thickness(0),
            };
            new Border
            {
                Background = Palette.Panel,
                BorderBrush = Palette.Bad,
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(14),
                Child = box,
            }.Place(_page, 64, 160, W - 128, 380);
            Ui.Label("If you ask for help, attach builder-log.txt from the builder's folder.", 19, Palette.Muted)
                .Place(_page, 64, 562);
            FooterButton("Open the log", Tone.Grey, 64, 240, () => Ui.Open(Builder.LogPath));
            FooterButton("Show the log file", Tone.Grey, 324, 260, () => Ui.Reveal(Builder.LogPath));
        }
        FooterButton("Back to start", Tone.Primary, W - 64 - 260, 260, ShowHome);
        FooterLine();
    }

    // --- Shared pieces ----------------------------------------------------------------------------

    /// <summary>The Switch launcher's title block: big title, a short coloured bar, a grey line.</summary>
    private void Header(string title, string subtitle, IBrush accent)
    {
        Ui.Label(title, 40, Palette.Text, FontWeight.Bold).Place(_page, 64, 36);
        new Border { Background = accent, CornerRadius = new CornerRadius(2) }.Place(_page, 64, 94, 56, 4);
        Ui.Label(subtitle, 22, Palette.Muted).Place(_page, 64, 106);
    }

    private void FooterLine() =>
        new Border { Background = Palette.Border }.Place(_page, 64, 648, W - 128, 1);

    private Tile FooterButton(string text, Tone tone, double x, double width, Action action)
    {
        var tile = Tile.Text(tone, text, 20, 12);
        tile.Place(_page, x, 662, width, 46);
        tile.Click += action;
        return tile;
    }

    private void Footer(string note, params (string Text, Tone Tone, Action Action)[] buttons)
    {
        var label = Ui.Label(note, 18, Palette.Muted);
        label.HorizontalAlignment = HorizontalAlignment.Right;
        new Border { Child = label }.Place(_page, W - 64 - 600, 674, 600);
        FooterLine();
        var x = 64.0;
        foreach (var (text, tone, action) in buttons)
        {
            FooterButton(text, tone, x, 200, action);
            x += 214;
        }
    }

    private TextBlock? _dialogNote;

    /// <summary>A box over the dimmed screen, like the Switch launcher's dialogs. Buttons marked to
    /// close do so before their action runs.</summary>
    private void ShowDialog(string title, string body, params (string Text, Tone Tone, bool Closes, Action Action)[] buttons)
    {
        _overlay.Children.Clear();
        _overlay.Children.Add(new Border { Background = Palette.Solid(0, 0, 0, 170) });
        var canvas = new Canvas { Width = W, Height = H };
        _overlay.Children.Add(canvas);
        const double x = 200, y = 130, w = 880, h = 460;
        new Border
        {
            Background = Palette.Panel,
            BorderBrush = Palette.Border,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(22),
            BoxShadow = new BoxShadows(new BoxShadow { OffsetY = 6, Blur = 24, Color = Color.FromArgb(140, 0, 0, 0) }),
        }.Place(canvas, x, y, w, h);
        Ui.Label(title, 34, Palette.Text, FontWeight.Bold).Place(canvas, x + 44, y + 36);
        Ui.Label(body, 21, Palette.Muted, FontWeight.Normal, w - 88).Place(canvas, x + 44, y + 96);
        _dialogNote = Ui.Label("", 19, Palette.Warn, FontWeight.SemiBold, w - 88).Place(canvas, x + 44, y + h - 150);
        var bw = (w - 88 - 16 * (buttons.Length - 1)) / buttons.Length;
        var bx = x + 44;
        Tile? first = null;
        foreach (var (text, tone, closes, action) in buttons)
        {
            var tile = Tile.Text(tone, text, 21);
            tile.Place(canvas, bx, y + h - 104, bw, 68);
            tile.Click += () =>
            {
                if (closes)
                {
                    CloseDialog();
                }
                action();
            };
            first ??= tile;
            bx += bw + 16;
        }
        _overlay.IsVisible = true;
        first?.Focus();
    }

    /// <summary>A grid of the languages; the choice is saved and applies the next time the builder opens.</summary>
    private void ShowLanguageDialog()
    {
        _overlay.Children.Clear();
        _overlay.Children.Add(new Border { Background = Palette.Solid(0, 0, 0, 170) });
        var canvas = new Canvas { Width = W, Height = H };
        _overlay.Children.Add(canvas);
        const double x = 140, y = 110, w = 1000, h = 500;
        new Border
        {
            Background = Palette.Panel,
            BorderBrush = Palette.Border,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(22),
        }.Place(canvas, x, y, w, h);
        Ui.Label("Language", 34, Palette.Text, FontWeight.Bold).Place(canvas, x + 44, y + 30);
        _dialogNote = Ui.Label("", 19, Palette.Warn, FontWeight.SemiBold, w - 88).Place(canvas, x + 44, y + h - 130);
        var saved = I18n.SavedChoice();
        var choices = new List<(string Code, string Name)> { ("auto", I18n.T("Automatic")) };
        choices.AddRange(I18n.Languages);
        const int columns = 4;
        const double tileW = 214, tileH = 62, gap = 16;
        for (var i = 0; i < choices.Count; i++)
        {
            var (code, name) = choices[i];
            var tile = Tile.Text(code == saved ? Tone.Primary : Tone.Grey, name, 20, 14);
            tile.Place(canvas, x + 44 + (i % columns) * (tileW + gap), y + 96 + (i / columns) * (tileH + gap), tileW, tileH);
            tile.Click += () =>
            {
                I18n.SaveChoice(code);
                DialogNote("Applies the next time the builder opens.");
                ShowLanguageDialog();
                DialogNote("Applies the next time the builder opens.");
            };
        }
        var close = Tile.Text(Tone.Normal, "Close", 21);
        close.Place(canvas, x + w - 44 - 260, y + h - 90, 260, 62);
        close.Click += CloseDialog;
        _overlay.IsVisible = true;
        close.Focus();
    }

    private void DialogNote(string text)
    {
        if (_dialogNote != null)
        {
            _dialogNote.Text = text;
        }
    }

    private void CloseDialog()
    {
        _overlay.IsVisible = false;
        _overlay.Children.Clear();
    }

    private void OnClosing(object? sender, WindowClosingEventArgs e)
    {
        if (!_building)
        {
            return;
        }
        e.Cancel = true;
        ShowDialog("Stop the build and close?",
            "The build is still running. Closing stops it, and the next build starts over. Downloads are kept.",
            ("Keep going", Tone.Primary, true, () => { }),
            ("Stop and close", Tone.Danger, true, () =>
            {
                _closeAfterStop = true;
                Cancel.Request();
            }));
    }

    private static string Duration(TimeSpan time) =>
        time.TotalMinutes < 1 ? "under a minute"
        : time.TotalHours < 1 ? $"{(int)time.TotalMinutes} min"
        : $"{(int)time.TotalHours} h {time.Minutes} min";

    // Windows would otherwise go to sleep in the middle of a long build when nobody touches it.
    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    private static void KeepAwake(bool on)
    {
        if (OperatingSystem.IsWindows())
        {
            const uint continuous = 0x80000000, systemRequired = 0x1;
            SetThreadExecutionState(on ? continuous | systemRequired : continuous);
        }
    }
}
