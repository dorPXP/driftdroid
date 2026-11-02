using System.Runtime.InteropServices;
using Avalonia;

namespace DriftDroidBuilder;

/// <summary>
/// DriftDroid Builder: makes the game code for DriftDroid (Android) and DriftDroid for Switch from
/// the player's own PAL Mario Kart Wii disc. The app downloads carry no game code; this program
/// translates the disc's code and compiles it on the player's computer.
///
/// Started without options (or with a disc image dropped on it) it opens its window. The options
/// below run it in the terminal instead: --android/--switch build (--no-retro-rewind leaves
/// Retro Rewind out), --console asks in the terminal,
/// --check-disc and --check-retro-rewind only check the inputs.
/// </summary>
public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        var windowed = !args.Any(a => a.StartsWith("--", StringComparison.Ordinal)) && args.Length <= 1 && HasDesktop();
        if (windowed)
        {
            try
            {
                Gui.App.StartDisc = args.FirstOrDefault(File.Exists);
                var app = AppBuilder.Configure<Gui.App>().UsePlatformDetect().WithInterFont();
                if (RunningUnderWine())
                {
                    // Wine has no DirectComposition and its Direct3D path hangs the window's
                    // drawing: draw on the CPU instead.
                    app = app.With(new Win32PlatformOptions
                    {
                        RenderingMode = new[] { Win32RenderingMode.Software },
                        CompositionMode = new[] { Win32CompositionMode.RedirectionSurface },
                    });
                }
                return app.StartWithClassicDesktopLifetime(args);
            }
            catch (Exception e) when (!Gui.App.Started)
            {
                // No usable display: fall back to the terminal.
                AttachToConsole();
                Console.WriteLine($"The window could not open ({e.Message}); continuing in the terminal.\n");
            }
        }
        else
        {
            AttachToConsole();
        }
        return RunInConsoleAsync(args).GetAwaiter().GetResult();
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
    private static extern IntPtr GetModuleHandleA(string name);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
    private static extern IntPtr GetProcAddress(IntPtr module, string name);

    private static bool RunningUnderWine()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }
        var ntdll = GetModuleHandleA("ntdll.dll");
        return ntdll != IntPtr.Zero && GetProcAddress(ntdll, "wine_get_version") != IntPtr.Zero;
    }

    private static bool HasDesktop() =>
        !OperatingSystem.IsLinux() ||
        !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DISPLAY")) ||
        !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("WAYLAND_DISPLAY"));

    private static async Task<int> RunInConsoleAsync(string[] args)
    {
        if (args.Length == 2 && args[0] == "--check-disc")
        {
            // Support: tells whether a disc image is one the builder can use.
            var check = Builder.CheckDisc(args[1]);
            Console.WriteLine($"{check.Title}: {check.Detail}");
            return check.Ok ? 0 : 1;
        }
        if (args.Length >= 1 && args[0] == "--check-retro-rewind")
        {
            // Support: which Retro Rewind code a build made now would use.
            try
            {
                var temp = Path.Combine(Path.GetTempPath(), $"ddb-rr-{Guid.NewGuid():N}");
                var code = await RetroRewindSource.FetchAsync(PinnedRetroRewind(args), Path.Combine(temp, "Code.pul"),
                    Path.Combine(temp, "cache"));
                Directory.Delete(temp, recursive: true);
                Console.WriteLine($"Retro Rewind {code.Version}: Code.pul from {code.CodeVersion}, sha256 {code.Sha256}");
                return 0;
            }
            catch (BuildException e)
            {
                Console.WriteLine(e.Message);
                return 1;
            }
        }

        Log.OpenFile(Builder.LogPath);
        try
        {
            Builder.CheckInstall();
            var iconAt = Array.IndexOf(args, "--switch-icon");
            if (args.Contains("--default-switch-icon"))
            {
                SwitchIcon.UseDefault();
            }
            else if (iconAt >= 0)
            {
                var problem = iconAt + 1 < args.Length ? SwitchIcon.Set(args[iconAt + 1]) : "--switch-icon needs a picture file.";
                if (problem != null)
                {
                    throw new BuildException(problem);
                }
            }
            var disc = args.Where((a, i) => !(iconAt >= 0 && i == iconAt + 1)).FirstOrDefault(File.Exists) ?? AskForDisc();
            var (android, nswitch) = AskTargets(args);
            await Builder.RunAsync(new BuildRequest(disc, android, nswitch, !args.Contains("--no-retro-rewind"),
                PinnedRetroRewind(args)));
            Pause();
            return 0;
        }
        catch (BuildException e)
        {
            Log.Info($"\nStopped: {e.Message}");
            Log.Info($"The full log is in {Builder.LogPath}.");
            Pause();
            return 1;
        }
        catch (Exception e)
        {
            Log.Info($"\nSomething went wrong: {e}");
            Log.Info($"Please report it with {Builder.LogPath} attached.");
            Pause();
            return 2;
        }
    }

    private static string AskForDisc()
    {
        while (true)
        {
            Console.WriteLine("Drag your Mario Kart Wii disc image (.iso or .wbfs) into this window, then press Enter:");
            var line = (Console.ReadLine() ?? "").Trim().Trim('"', '\'', ' ');
            if (File.Exists(line))
            {
                return line;
            }
            Console.WriteLine($"Can't find '{line}'. Try again.\n");
        }
    }

    /// <summary>"--retro-rewind 6.13.3" builds for that Retro Rewind release instead of the newest.</summary>
    private static string? PinnedRetroRewind(string[] args)
    {
        var at = Array.IndexOf(args, "--retro-rewind");
        return at >= 0 && at + 1 < args.Length ? args[at + 1] : null;
    }

    private static (bool Android, bool Switch) AskTargets(string[] args)
    {
        if (args.Contains("--android"))
        {
            return (true, args.Contains("--switch"));
        }
        if (args.Contains("--switch"))
        {
            return (false, true);
        }
        Console.WriteLine("\nWhat do you want to build?");
        Console.WriteLine("  1 = Android (phone)");
        Console.WriteLine("  2 = Switch");
        Console.WriteLine("  3 = Both");
        Console.Write("Type 1, 2 or 3 and press Enter [1]: ");
        var answer = (Console.ReadLine() ?? "").Trim();
        return answer switch
        {
            "2" => (false, true),
            "3" => (true, true),
            _ => (true, false),
        };
    }

    private static void Pause()
    {
        if (!Console.IsInputRedirected)
        {
            Console.WriteLine("\nPress Enter to close.");
            Console.ReadLine();
        }
    }

    // On Windows the program is a window program, which has no console of its own: the terminal
    // modes use the console it was started from, or open one.
    [DllImport("kernel32.dll")]
    private static extern bool AttachConsole(int processId);

    [DllImport("kernel32.dll")]
    private static extern bool AllocConsole();

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetStdHandle(int which);

    [DllImport("kernel32.dll")]
    private static extern uint GetFileType(IntPtr handle);

    private static void AttachToConsole()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }
        // Output sent to a file or another program already has somewhere to go.
        const int stdOutput = -11;
        const uint fileTypeDisk = 1, fileTypePipe = 3;
        var type = GetFileType(GetStdHandle(stdOutput));
        if (type is fileTypeDisk or fileTypePipe)
        {
            return;
        }
        if (!AttachConsole(-1))
        {
            AllocConsole();
        }
    }
}
