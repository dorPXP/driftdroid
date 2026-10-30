using System.IO.Compression;
using System.Text.Json;

namespace DriftDroidBuilder;

/// <param name="RetroRewind">Also build Retro Rewind (the second game: libGameCombined.so,
/// RetroRewind.nro). Without it the build is quicker; its code is still fetched and translated,
/// because the base game's translation depends on it.</param>
/// <param name="RetroRewindVersion">Build for this Retro Rewind release instead of the newest.</param>
public sealed record BuildRequest(string Disc, bool Android, bool Switch, bool RetroRewind, string? RetroRewindVersion);

public sealed record BuildResult(string? AndroidPack, List<string> SwitchFiles, RetroRewindSource.Code RetroRewind);

/// <summary>
/// One build from a disc image to the finished files, shared by the window and the command line.
/// Progress goes out through <see cref="Log"/>; <see cref="Cancel"/> stops it.
/// </summary>
public static class Builder
{
    public const string Version = "1.4.0";

    public static string BaseDir => AppContext.BaseDirectory;
    public static string ToolkitDir => Path.Combine(BaseDir, "toolkit");
    public static string WorkDir => Path.Combine(BaseDir, "work");
    public static string OutputDir => Path.Combine(BaseDir, "output");
    public static string CacheDir => Path.Combine(BaseDir, "downloads");
    public static string LogPath => Path.Combine(BaseDir, "builder-log.txt");

    public sealed record ToolkitInfo(string Id, string Version);

    public static ToolkitInfo ReadToolkitInfo()
    {
        var path = Path.Combine(ToolkitDir, "toolkit.json");
        if (!File.Exists(path))
        {
            throw new BuildException(I18n.T("The 'toolkit' folder is missing. Unpack the whole DriftDroid Builder download, not just the program."));
        }
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return new ToolkitInfo(doc.RootElement.GetProperty("id").GetString()!, doc.RootElement.GetProperty("version").GetString()!);
    }

    /// <summary>Problems with where the builder was unpacked, which no choice in the window fixes.</summary>
    public static void CheckInstall()
    {
        ReadToolkitInfo();
        if (BaseDir.Any(c => c > 127))
        {
            throw new BuildException(
                $"The builder's folder path has characters the compilers may not handle ({BaseDir}).\n" +
                "Move the whole DriftDroid Builder folder somewhere like C:\\DriftDroidBuilder and run it from there.");
        }
    }

    public sealed record DiscCheck(bool Ok, string Title, string Detail);

    /// <summary>Whether a disc image is one the builder can use, in words for the player.</summary>
    public static DiscCheck CheckDisc(string path)
    {
        try
        {
            using var disc = new WiiDisc(path);
            var dol = Files.Sha256(disc.ReadMainDol());
            var rel = Files.Sha256(disc.ReadFile("rel/StaticR.rel"));
            if (dol == Translation.MainDolSha256 && rel == Translation.StaticRelSha256)
            {
                return new DiscCheck(true, "Mario Kart Wii (PAL): ready", Path.GetFileName(path));
            }
            return new DiscCheck(false, "This disc can't be used",
                "DriftDroid needs an unmodified PAL (European) Mario Kart Wii disc, game ID RMCP01.");
        }
        catch (BuildException e)
        {
            return new DiscCheck(false, "This file can't be read", e.Message);
        }
        catch (IOException e)
        {
            return new DiscCheck(false, "This file can't be read", e.Message);
        }
        catch (UnauthorizedAccessException e)
        {
            return new DiscCheck(false, "This file can't be opened", e.Message);
        }
    }

    public static bool SwitchCompilerInstalled()
    {
        try
        {
            Toolchains.Switch();
            return true;
        }
        catch (BuildException)
        {
            return false;
        }
    }

    /// <summary>Free space the build needs, in GB: translation about 1 GB, each platform 3-5 GB of
    /// objects, the Android compiler 3 GB.</summary>
    public static long NeededGb(bool android, bool nswitch) => (android ? 8L : 0) + (nswitch ? 6L : 0) + 2;

    public static long FreeGb() =>
        new DriveInfo(Path.GetPathRoot(Path.GetFullPath(BaseDir))!).AvailableFreeSpace / (1L << 30);

    public static bool SkipSpaceCheck => Environment.GetEnvironmentVariable("DRIFTDROID_SKIP_SPACE_CHECK") == "1";

    public static long WorkFolderBytes()
    {
        if (!Directory.Exists(WorkDir))
        {
            return 0;
        }
        try
        {
            return Directory.EnumerateFiles(WorkDir, "*", SearchOption.AllDirectories).Sum(f => new FileInfo(f).Length);
        }
        catch (IOException)
        {
            return 0;
        }
    }

    public static async Task<BuildResult> RunAsync(BuildRequest request)
    {
        Log.Info($"DriftDroid Builder {Version}");
        Log.Info("Makes the game code for DriftDroid from your own Mario Kart Wii disc (PAL).");
        Log.Info("Nothing is uploaded anywhere; everything happens on this computer.\n");

        CheckInstall();
        var info = ReadToolkitInfo();
        var needed = NeededGb(request.Android, request.Switch);
        var free = FreeGb();
        if (free < needed && !SkipSpaceCheck)
        {
            throw new BuildException($"Not enough free disk space: about {needed} GB is needed and {free} GB is free on this drive.");
        }

        var number = 0;
        var repo = Path.Combine(WorkDir, "repo");
        Log.Step(Stage.Prepare, $"{++number}. Preparing");
        if (Directory.Exists(WorkDir))
        {
            Directory.Delete(WorkDir, recursive: true);
        }
        Files.CopyDirectory(Path.Combine(ToolkitDir, "repo"), repo);
        Cancel.ThrowIfRequested();

        Log.Step(Stage.Disc, $"{++number}. Reading your disc");
        Translation.ExtractGameFiles(request.Disc, repo);
        Cancel.ThrowIfRequested();

        Log.Step(Stage.RetroRewind, $"{++number}. Getting Retro Rewind's game code");
        var retroRewind = await Translation.FetchRetroRewindCodeAsync(repo, CacheDir, request.RetroRewindVersion);
        Cancel.ThrowIfRequested();

        Log.Step(Stage.Translate, $"{++number}. Translating the game code (15-40 minutes)");
        Translation.Run(Path.Combine(ToolkitDir, "translator"), repo);

        Directory.CreateDirectory(OutputDir);
        var jobs = CompileJobs();
        string? pack = null;
        var nros = new List<string>();
        if (request.Android)
        {
            Log.Step(Stage.Android, $"{++number}. Building for Android (30-90 minutes)");
            var llvm = await Toolchains.AndroidAsync(CacheDir);
            pack = await BuildAndroidAsync(repo, Path.Combine(WorkDir, "android"), llvm, jobs, info,
                request.RetroRewind ? retroRewind : null);
            Log.Info($"\nAndroid game pack ready:\n  {pack}");
        }
        if (request.Switch)
        {
            Log.Step(Stage.Switch, $"{++number}. Building for Switch (30-90 minutes)");
            var dkp = Toolchains.Switch();
            nros = BuildSwitch(repo, Path.Combine(WorkDir, "switch"), dkp, jobs, request.RetroRewind);
            Log.Info("\nSwitch files ready:\n  " + string.Join("\n  ", nros));
        }

        Log.Step(Stage.Done, "Done");
        if (request.Android)
        {
            Log.Info("Android: copy the game pack .zip to your phone, open DriftDroid and tap \"Import game pack\".");
        }
        if (request.Switch)
        {
            Log.Info(request.RetroRewind
                ? "Switch: copy DriftDroid.nro to /switch/ and RetroRewind.nro to /switch/WiiCompiled/ on the SD card."
                : "Switch: copy DriftDroid.nro to /switch/ on the SD card.");
        }
        Log.Info("You can delete the 'work' folder now to free space.");
        return new BuildResult(pack, nros, retroRewind);
    }

    private static int CompileJobs()
    {
        if (int.TryParse(Environment.GetEnvironmentVariable("DRIFTDROID_JOBS"), out var forced) && forced > 0)
        {
            return forced;
        }
        var ramGb = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1L << 30);
        // A big translated file can take about 2.5 GB of memory to compile.
        var byRam = (int)Math.Max(1, ramGb / 3);
        return Math.Clamp(Math.Min(Environment.ProcessorCount, byRam), 1, 16);
    }

    // What only Retro Rewind needs: its compile groups (mkw_retro_sensitive, mkw_retro_rewind_functions,
    // RetroRewind.dir) and its libraries.
    private static bool RetroRewindGroup(string id) => id.Contains("retro", StringComparison.OrdinalIgnoreCase);

    private static bool RetroRewindOutput(string output) =>
        output is "libRetroRewind.so" or "libGameCombined.so" or "RetroRewind.elf";

    private static PlanRunner Runner(BuildPlan plan, string repo, string dir, string toolchain, bool retroRewind) =>
        new(plan, repo, dir, toolchain, Path.Combine(ToolkitDir, "pgo"))
        {
            SkipGroup = id => !retroRewind && RetroRewindGroup(id),
            SkipStep = output => !retroRewind && RetroRewindOutput(output),
        };

    /// <param name="retroRewind">The Retro Rewind code built in, or null when it is left out.</param>
    private static async Task<string> BuildAndroidAsync(string repo, string dir, string llvm, int jobs, ToolkitInfo info,
        RetroRewindSource.Code? retroRewind)
    {
        Log.Info("  copying the prebuilt parts...");
        Files.CopyDirectory(Path.Combine(ToolkitDir, "android", "prebuilt"), dir);
        var plan = BuildPlan.Load(Path.Combine(ToolkitDir, "android", "plan.json"));
        var runner = Runner(plan, repo, dir, llvm, retroRewind != null);
        Log.Info($"  compiling the game code with {jobs} parallel jobs...");
        runner.Compile(jobs);
        var bin = Path.Combine(llvm, "bin");
        Log.Progress("Linking", null);
        runner.RunSteps(Path.Combine(bin, "llvm-objcopy"), Path.Combine(bin, "llvm-nm"));

        Log.Info("  packing...");
        Log.Progress("Packing the game pack", null);
        var strip = Path.Combine(bin, OperatingSystem.IsWindows() ? "llvm-strip.exe" : "llvm-strip");
        var pack = Path.Combine(OutputDir, $"DriftDroid-GamePack-{info.Version}.zip");
        if (File.Exists(pack))
        {
            File.Delete(pack);
        }
        using (var zip = ZipFile.Open(pack, ZipArchiveMode.Create))
        {
            var libs = retroRewind != null ? new[] { "libWiiCompiled.so", "libGameCombined.so" } : new[] { "libWiiCompiled.so" };
            foreach (var lib in libs)
            {
                var stripped = Path.Combine(dir, "stripped-" + lib);
                Proc.RunChecked($"Stripping {lib}", strip, new[] { "--strip-unneeded", lib, "-o", stripped }, dir);
                zip.CreateEntryFromFile(stripped, lib, CompressionLevel.Optimal);
            }
            var manifest = zip.CreateEntry("driftdroid-pack.json");
            await using var writer = new StreamWriter(manifest.Open());
            await writer.WriteAsync(JsonSerializer.Serialize(new
            {
                toolkit = info.Id,
                version = info.Version,
                retroRewind = retroRewind?.Version,
                retroRewindCodeSha256 = retroRewind?.Sha256,
            }));
        }
        return pack;
    }

    private static List<string> BuildSwitch(string repo, string dir, string dkp, int jobs, bool retroRewind)
    {
        Log.Info("  copying the prebuilt parts...");
        Files.CopyDirectory(Path.Combine(ToolkitDir, "switch", "prebuilt"), dir);
        var plan = BuildPlan.Load(Path.Combine(ToolkitDir, "switch", "plan.json"));
        var runner = Runner(plan, repo, dir, dkp, retroRewind);
        Log.Info($"  compiling the game code with {jobs} parallel jobs...");
        runner.Compile(jobs);
        Log.Progress("Linking", null);
        runner.RunSteps("", "");
        Log.Progress("Packing the .nro files", null);
        return SwitchPackaging.MakeNros(ToolkitDir, dir, dkp, OutputDir, retroRewind);
    }
}
