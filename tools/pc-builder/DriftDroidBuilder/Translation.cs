namespace DriftDroidBuilder;

/// <summary>
/// Turns the player's own game files into C++: the same five translator steps the DriftDroid
/// release builds use, run inside the toolkit's copy of the repository.
/// </summary>
public static class Translation
{
    // Pinned in projects/mkwii/recomp.yml; checked here first so a wrong disc fails with a clear
    // message instead of deep inside the translator.
    public const string MainDolSha256 = "80d18895b39c63bd80f457398bfcbb91b7d16ac116a41a88967e954080155b05";
    public const string StaticRelSha256 = "16d9d146112541fefea701ecb5bc1a496f9d50e4a752fbb5b6778e7c6399f67d";

    public static void ExtractGameFiles(string discPath, string repo)
    {
        Log.Info("  reading the disc image...");
        using var disc = new WiiDisc(discPath);
        var dol = disc.ReadMainDol();
        var rel = disc.ReadFile("rel/StaticR.rel");
        if (Files.Sha256(dol) != MainDolSha256 || Files.Sha256(rel) != StaticRelSha256)
        {
            throw new BuildException(
                "This disc's game files are not the ones DriftDroid was made for. It needs an unmodified PAL Mario Kart Wii disc (RMCP01).");
        }
        var assets = Path.Combine(repo, "Assets");
        Directory.CreateDirectory(assets);
        File.WriteAllBytes(Path.Combine(assets, "main.dol"), dol);
        File.WriteAllBytes(Path.Combine(assets, "StaticR.rel"), rel);
        Log.Info("  PAL Mario Kart Wii found.");
    }

    /// <summary>Retro Rewind's game logic is not on the Wii disc: it comes from the mod's own update
    /// server, newest release unless <paramref name="pinnedVersion"/> names one.</summary>
    public static Task<RetroRewindSource.Code> FetchRetroRewindCodeAsync(string repo, string cache, string? pinnedVersion)
    {
        var binaries = Path.Combine(repo, "PulsarPacks", "completed", "RetroRewind", "RetroRewind6", "Binaries");
        return RetroRewindSource.FetchAsync(pinnedVersion, Path.Combine(binaries, "Code.pul"), cache);
    }

    public static void Run(string translatorDir, string repo)
    {
        var translator = Path.Combine(translatorDir, OperatingSystem.IsWindows() ? "Translator.Cli.exe" : "Translator.Cli");
        if (!File.Exists(translator))
        {
            throw new BuildException($"The translator is missing ({translator}).");
        }
        var threads = Math.Clamp(Environment.ProcessorCount, 1, 16).ToString();
        // Two passes: the base game's translation takes into account what the Retro Rewind step
        // wrote on the previous pass (base_translation_mod_awareness.json), so only the second pass
        // gives exactly the code the DriftDroid releases are built and tested with.
        for (var pass = 1; pass <= 2; pass++)
        {
            RunPass(translator, repo, threads, pass);
        }
    }

    private static void RunPass(string translator, string repo, string threads, int pass)
    {
        const string project = "projects/mkwii/recomp.yml";
        const string rr = "PulsarPacks/completed/RetroRewind/RetroRewind6";

        var step = 0;
        void Step(string label, params string[] args)
        {
            step++;
            Log.Info($"  {label} (pass {pass} of 2)...");
            Log.Progress($"Pass {pass} of 2, part {step} of 5: {label}", null);
            // Android and Switch are ELF targets, also when the translator runs on Windows.
            Proc.RunChecked($"Translation ({label})", translator, args, repo,
                new Dictionary<string, string> { ["MKW_TRANSLATOR_ELF"] = "1" });
        }

        Step("translating Mario Kart Wii (the longest step)", "translate-recursive", "0x800060A4", "--project", project,
            "--outdir", "generated/functions", "--output-metadata", "generated/base_translation_output.json",
            "--production-source-bundle", "generated/base_translation_sources.bin", "--no-function-files", "--prune-stale",
            "--threads", threads);
        Step("indexing", "emit-base-manifest", "--project", project, "--out", "build/base", "--functions-dir",
            "generated/functions", "--translation-output-metadata", "generated/base_translation_output.json", "--region", "P");
        Step("translating Retro Rewind", "translate-mod", "--project", project, "--profile", "retro-rewind",
                "--base-manifest", "build/base/mkwii_base_manifest.json", "--base-translation-output-metadata",
                "generated/base_translation_output.json", "--code-pul", $"{rr}/Binaries/Code.pul", "--mod-root", rr,
                "--mod-name", "Retro Rewind", "--region", "P", "--out", "build/mods/retro_rewind_full_cpp",
                "--prefer-cached-inputs", "--emit-cpp", "--threads", threads);
        Step("game data", "generate-data-init", "--project", project);
        var shards = new List<string>
        {
            "emit-build-shards", "--project", project, "--base-metadata", "generated/base_translation_output.json",
            "--base-functions-dir", "generated/functions", "--native-source-dir", "runtime/src", "--out", "generated/build_shards",
            "--resolved-profile", "build/mods/retro_rewind_full_cpp/resolved_dispatch_profile.json",
            "--retro-cpp-dir", "build/mods/retro_rewind_full_cpp/cpp",
        };
        Step("splitting into build pieces", shards.ToArray());
    }
}
