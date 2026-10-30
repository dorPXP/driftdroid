using System.Diagnostics;
using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;

namespace DriftDroidBuilder.Gui;

/// <summary>The Switch launcher's colours (runtime/src/switch_launcher.cpp), which are the Android
/// launcher's.</summary>
public static class Palette
{
    public static readonly Color CheckLight = Color.FromRgb(0x3A, 0x3A, 0x3A);
    public static readonly Color CheckDark = Color.FromRgb(0x22, 0x22, 0x22);
    public static readonly IBrush Panel = Solid(0x3A, 0x3A, 0x3A);
    public static readonly IBrush PanelHi = Solid(0x5A, 0x5A, 0x5A);
    public static readonly IBrush Border = Solid(0x66, 0x66, 0x66);
    public static readonly IBrush Text = Solid(236, 240, 247);
    public static readonly IBrush Muted = Solid(0xB4, 0xB4, 0xB4);
    public static readonly IBrush Accent = Solid(0x1C, 0x5F, 0xD1);
    public static readonly IBrush AccentHi = Solid(0x4F, 0xA6, 0xFF);
    public static readonly IBrush Good = Solid(63, 185, 80);
    public static readonly IBrush Warn = Solid(232, 171, 42);
    public static readonly IBrush Bad = Solid(248, 81, 73);
    public static readonly IBrush BadDark = Solid(110, 34, 34);
    public static readonly IBrush White = Solid(255, 255, 255);

    public static IBrush Solid(byte r, byte g, byte b, byte a = 255) => new SolidColorBrush(Color.FromArgb(a, r, g, b));

    /// <summary>The Android launcher's two-colour button fill, top to bottom.</summary>
    public static IBrush Vertical(Color top, Color bottom) => new LinearGradientBrush
    {
        StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
        EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
        GradientStops = { new GradientStop(top, 0), new GradientStop(bottom, 1) },
    };
}

public static class Ui
{
    public static TextBlock Label(string text, double size, IBrush? color = null, FontWeight weight = FontWeight.Normal,
        double? width = null)
    {
        var block = new TextBlock
        {
            Text = I18n.T(text),
            FontSize = size,
            Foreground = color ?? Palette.Text,
            FontWeight = weight,
        };
        if (width is { } w)
        {
            block.Width = w;
            block.TextWrapping = TextWrapping.Wrap;
        }
        return block;
    }

    /// <summary>Puts a control at a spot of the 1280x720 layout, as the Switch launcher does.</summary>
    public static T Place<T>(this T control, Canvas canvas, double x, double y, double? width = null, double? height = null)
        where T : Control
    {
        Canvas.SetLeft(control, x);
        Canvas.SetTop(control, y);
        if (width is { } w)
        {
            control.Width = w;
        }
        if (height is { } h)
        {
            control.Height = h;
        }
        canvas.Children.Add(control);
        return control;
    }

    public static Bitmap Art(string name) =>
        new(Assembly.GetExecutingAssembly().GetManifestResourceStream(name) ?? throw new InvalidOperationException(name));

    /// <summary>Opens a file or web page with whatever the system uses for it.</summary>
    public static void Open(string target)
    {
        try
        {
            if (OperatingSystem.IsWindows())
            {
                Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
            }
            else
            {
                Process.Start(new ProcessStartInfo("xdg-open") { ArgumentList = { target }, UseShellExecute = false });
            }
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Detail($"could not open {target}: {e.Message}");
        }
    }

    /// <summary>Shows a file in its folder (selected, on Windows), or opens a folder.</summary>
    public static void Reveal(string path)
    {
        if (OperatingSystem.IsWindows() && File.Exists(path))
        {
            try
            {
                Process.Start("explorer.exe", $"/select,\"{path}\"");
                return;
            }
            catch (System.ComponentModel.Win32Exception)
            {
            }
        }
        Open(Directory.Exists(path) ? path : System.IO.Path.GetDirectoryName(path)!);
    }
}

/// <summary>The Android launcher's background: a dark checkerboard drifting slowly down and to the
/// right.</summary>
public sealed class Checkerboard : Control
{
    private const double TilesPerSecond = 0.1;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private static readonly IBrush Dark = new SolidColorBrush(Palette.CheckDark);
    private static readonly IBrush Light = new SolidColorBrush(Palette.CheckLight);

    public override void Render(DrawingContext context)
    {
        var size = Bounds.Size;
        // 42 units of the 1280x720 layout, like the Switch launcher (28dp on Android).
        var tile = 42.0 * Math.Max(0.5, Math.Min(size.Width / 1280, size.Height / 720));
        var period = tile * 2;
        var seconds = _clock.Elapsed.TotalSeconds;
        var offX = seconds * TilesPerSecond * tile % period;
        var offY = seconds * TilesPerSecond * tile * 0.6 % period;
        context.FillRectangle(Dark, new Rect(size));
        var row = 0;
        for (var y = offY - period; y < size.Height; y += tile, row++)
        {
            for (var x = offX - period + (row % 2 != 0 ? tile : 0); x < size.Width; x += period)
            {
                context.FillRectangle(Light, new Rect(x, y, tile, tile));
            }
        }
    }
}

public enum Tone
{
    /// <summary>A flat grey panel with a thin border (the Switch launcher's big buttons).</summary>
    Normal,
    /// <summary>Blue: the one thing to press next.</summary>
    Primary,
    /// <summary>Dark red: stopping something.</summary>
    Danger,
    /// <summary>The Android launcher's small grey utility buttons.</summary>
    Grey,
}

/// <summary>
/// A button drawn the way the launchers draw theirs: rounded, a white ring when the pointer or the
/// keyboard is on it, a small squash when pressed. <see cref="Selected"/> marks a chosen option.
/// </summary>
public sealed class Tile : Border
{
    private readonly Tone _tone;
    private bool _hover;
    private bool _pressed;
    private bool _selected;

    public event Action? Click;

    public Tile(Tone tone, Control content, double radius = 16)
    {
        _tone = tone;
        Child = content;
        CornerRadius = new CornerRadius(radius);
        BorderThickness = new Thickness(3);
        Focusable = true;
        Cursor = new Cursor(StandardCursorType.Hand);
        RenderTransformOrigin = RelativePoint.Center;
        Update();
    }

    /// <summary>A tile holding one centred line of text.</summary>
    public static Tile Text(Tone tone, string text, double size = 24, double radius = 16)
    {
        var label = Ui.Label(text, size, Palette.Text, FontWeight.SemiBold);
        label.HorizontalAlignment = HorizontalAlignment.Center;
        label.VerticalAlignment = VerticalAlignment.Center;
        if (tone == Tone.Grey)
        {
            label.Foreground = Palette.Solid(0xDD, 0xDD, 0xDD);
        }
        return new Tile(tone, label, radius);
    }

    public bool Selected
    {
        get => _selected;
        set
        {
            _selected = value;
            Update();
        }
    }

    protected override void OnPointerEntered(PointerEventArgs e)
    {
        base.OnPointerEntered(e);
        _hover = true;
        Update();
    }

    protected override void OnPointerExited(PointerEventArgs e)
    {
        base.OnPointerExited(e);
        _hover = false;
        _pressed = false;
        Update();
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            _pressed = true;
            e.Handled = true;
            Update();
        }
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        var fire = _pressed && _hover;
        _pressed = false;
        Update();
        if (fire)
        {
            e.Handled = true;
            Click?.Invoke();
        }
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Key is Key.Enter or Key.Space)
        {
            e.Handled = true;
            Click?.Invoke();
        }
    }

    protected override void OnGotFocus(Avalonia.Input.GotFocusEventArgs e)
    {
        base.OnGotFocus(e);
        Update();
    }

    protected override void OnLostFocus(Avalonia.Interactivity.RoutedEventArgs e)
    {
        base.OnLostFocus(e);
        Update();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsEnabledProperty)
        {
            Update();
        }
    }

    private void Update()
    {
        var active = IsEnabled && (_hover || IsFocused);
        Background = _tone switch
        {
            _ when !IsEnabled => Palette.Solid(0x2A, 0x2A, 0x2A),
            Tone.Primary => active ? Palette.AccentHi : Palette.Accent,
            Tone.Danger => active ? Palette.Bad : Palette.BadDark,
            Tone.Grey => active
                ? Palette.Vertical(Color.FromRgb(0x6A, 0x6A, 0x6A), Color.FromRgb(0x48, 0x48, 0x48))
                : Palette.Vertical(Color.FromRgb(0x5A, 0x5A, 0x5A), Color.FromRgb(0x3A, 0x3A, 0x3A)),
            _ => active ? Palette.PanelHi : Palette.Panel,
        };
        BorderBrush = active ? Palette.White
            : _selected ? Palette.AccentHi
            : _tone == Tone.Normal && IsEnabled ? Palette.Border
            : Brushes.Transparent;
        Opacity = IsEnabled ? 1 : 0.5;
        var scale = _pressed ? 0.97 : 1.0;
        RenderTransform = new ScaleTransform(scale, scale);
    }
}

/// <summary>A progress bar: a fraction, or a sliding block while the amount is not known.</summary>
public sealed class Bar : Control
{
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public double? Value { get; set; }

    public override void Render(DrawingContext context)
    {
        var size = Bounds.Size;
        var radius = size.Height / 2;
        context.DrawRectangle(Palette.Solid(0x22, 0x22, 0x22), new Pen(Palette.Border), new Rect(size), radius, radius);
        if (Value is { } v)
        {
            var w = Math.Max(size.Height, size.Width * Math.Clamp(v, 0, 1));
            context.DrawRectangle(Palette.Accent, null, new Rect(0, 0, w, size.Height), radius, radius);
        }
        else
        {
            // One pass every two seconds, coming in from the left edge and leaving at the right.
            var chunk = size.Width * 0.25;
            var t = _clock.Elapsed.TotalSeconds / 2 % 1;
            var x = -chunk + t * (size.Width + chunk);
            using (context.PushClip(new RoundedRect(new Rect(size), radius)))
            {
                context.DrawRectangle(Palette.Accent, null, new Rect(x, 0, chunk, size.Height), radius, radius);
            }
        }
    }
}

public enum StepState
{
    Waiting,
    Working,
    Done,
    Failed,
}

/// <summary>The round mark in front of a checklist line.</summary>
public sealed class StepMark : Control
{
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public StepState State { get; set; }

    public override void Render(DrawingContext context)
    {
        var r = Math.Min(Bounds.Width, Bounds.Height) / 2;
        var c = new Point(Bounds.Width / 2, Bounds.Height / 2);
        switch (State)
        {
            case StepState.Waiting:
                context.DrawEllipse(null, new Pen(Palette.Border, 3), c, r - 2, r - 2);
                break;
            case StepState.Working:
            {
                context.DrawEllipse(null, new Pen(Palette.Solid(0x1C, 0x5F, 0xD1, 110), 4), c, r - 3, r - 3);
                // A quarter circle going round.
                var angle = _clock.Elapsed.TotalSeconds * 2 * Math.PI * 0.8;
                var a = new Point(c.X + (r - 3) * Math.Cos(angle), c.Y + (r - 3) * Math.Sin(angle));
                var b = new Point(c.X + (r - 3) * Math.Cos(angle + Math.PI / 2), c.Y + (r - 3) * Math.Sin(angle + Math.PI / 2));
                var arc = new StreamGeometry();
                using (var g = arc.Open())
                {
                    g.BeginFigure(a, false);
                    g.ArcTo(b, new Size(r - 3, r - 3), 0, false, SweepDirection.Clockwise);
                    g.EndFigure(false);
                }
                context.DrawGeometry(null, new Pen(Palette.AccentHi, 4, lineCap: PenLineCap.Round), arc);
                break;
            }
            case StepState.Done:
                context.DrawEllipse(Palette.Good, null, c, r, r);
                context.DrawGeometry(null, new Pen(Palette.White, 3.5, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round),
                    Geometry.Parse(FormattableString.Invariant(
                        $"M {c.X - r * 0.42},{c.Y + r * 0.02} L {c.X - r * 0.1},{c.Y + r * 0.34} L {c.X + r * 0.45},{c.Y - r * 0.3}")));
                break;
            case StepState.Failed:
            {
                context.DrawEllipse(Palette.Bad, null, c, r, r);
                var d = r * 0.32;
                var pen = new Pen(Palette.White, 3.5, lineCap: PenLineCap.Round);
                context.DrawLine(pen, new Point(c.X - d, c.Y - d), new Point(c.X + d, c.Y + d));
                context.DrawLine(pen, new Point(c.X + d, c.Y - d), new Point(c.X - d, c.Y + d));
                break;
            }
        }
    }
}

/// <summary>Small drawings for the choices: a disc, a phone, a Switch.</summary>
public static class Icons
{
    public static Control Disc(double size)
    {
        var canvas = new Canvas { Width = size, Height = size };
        new Ellipse { Fill = Palette.Vertical(Color.FromRgb(0xF4, 0xF4, 0xF8), Color.FromRgb(0xA8, 0xB0, 0xC0)) }
            .Place(canvas, 0, 0, size, size);
        new Ellipse { Stroke = Palette.Solid(255, 255, 255, 160), StrokeThickness = 2 }
            .Place(canvas, size * 0.18, size * 0.18, size * 0.64, size * 0.64);
        new Ellipse { Fill = Palette.Solid(0x3A, 0x3A, 0x3A), Stroke = Palette.Solid(0x88, 0x88, 0x88), StrokeThickness = 2 }
            .Place(canvas, size * 0.38, size * 0.38, size * 0.24, size * 0.24);
        return canvas;
    }

    public static Control Phone(double height)
    {
        var w = height * 0.55;
        var canvas = new Canvas { Width = w, Height = height };
        new Rectangle { Fill = Palette.Solid(0x1A, 0x1A, 0x1A), Stroke = Palette.Solid(0xDD, 0xDD, 0xDD), StrokeThickness = 3, RadiusX = 9, RadiusY = 9 }
            .Place(canvas, 0, 0, w, height);
        new Rectangle { Fill = Palette.Vertical(Color.FromRgb(0x4F, 0xA6, 0xFF), Color.FromRgb(0x1C, 0x5F, 0xD1)), RadiusX = 3, RadiusY = 3 }
            .Place(canvas, 6, 10, w - 12, height - 24);
        new Rectangle { Fill = Palette.Solid(0xDD, 0xDD, 0xDD), RadiusX = 2, RadiusY = 2 }
            .Place(canvas, w * 0.35, height - 9, w * 0.3, 3);
        return canvas;
    }

    public static Control Switch(double height)
    {
        var con = height * 0.32;
        var screen = height * 1.25;
        var canvas = new Canvas { Width = con * 2 + screen, Height = height };
        new Rectangle { Fill = Palette.Solid(0x00, 0xB8, 0xD9), RadiusX = con * 0.5, RadiusY = con * 0.5 }
            .Place(canvas, 0, 0, con + 8, height);
        new Rectangle { Fill = Palette.Solid(0xFF, 0x45, 0x54), RadiusX = con * 0.5, RadiusY = con * 0.5 }
            .Place(canvas, con + screen - 8, 0, con + 8, height);
        new Rectangle { Fill = Palette.Solid(0x22, 0x22, 0x22), Stroke = Palette.Solid(0x55, 0x55, 0x55), StrokeThickness = 2 }
            .Place(canvas, con, 0, screen, height);
        new Rectangle { Fill = Palette.Vertical(Color.FromRgb(0x4F, 0xA6, 0xFF), Color.FromRgb(0x1C, 0x5F, 0xD1)) }
            .Place(canvas, con + 7, 7, screen - 14, height - 14);
        new Ellipse { Fill = Palette.Solid(0x22, 0x22, 0x22) }.Place(canvas, con * 0.5 - 2, height * 0.28, 12, 12);
        new Ellipse { Fill = Palette.Solid(0x22, 0x22, 0x22) }.Place(canvas, con * 1.5 + screen - 10, height * 0.55, 12, 12);
        return canvas;
    }

    /// <summary>The green tick in the corner of a chosen option.</summary>
    public static Control Check(double size) => new StepMark { State = StepState.Done, Width = size, Height = size };

    /// <summary>A numbered circle in front of a section heading.</summary>
    public static Control Number(int n, double size = 36)
    {
        var label = Ui.Label(n.ToString(), size * 0.58, Palette.White, FontWeight.Bold);
        label.HorizontalAlignment = HorizontalAlignment.Center;
        label.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(size / 2),
            Background = Palette.Accent,
            Child = label,
        };
    }
}
