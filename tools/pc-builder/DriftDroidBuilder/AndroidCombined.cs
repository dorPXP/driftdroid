namespace DriftDroidBuilder;

/// <summary>
/// libGameCombined.so: Mario Kart Wii and Retro Rewind in one Android library. A port of
/// runtime/cmake/build_combined_android_lib.py, which explains why it exists: translated functions
/// that compile differently for the two products share a name (func_ADDRESS), so the Retro Rewind
/// copies are renamed to func_ADDRESS__rr before both are linked together.
/// </summary>
public static class AndroidCombined
{
    private static readonly string[] RetroDirs =
    {
        "CMakeFiles/mkw_retro_sensitive.dir",
        "CMakeFiles/mkw_retro_rewind_functions.dir",
        "CMakeFiles/RetroRewind.dir",
    };

    private static bool IsRetroExclusive(string token) =>
        token.Contains("CMakeFiles/mkw_retro_sensitive.dir/") ||
        token.Contains("CMakeFiles/mkw_retro_rewind_functions.dir/") ||
        token.Contains("CMakeFiles/RetroRewind.dir/");

    private static List<string> Objects(string work, string dir) =>
        Directory.Exists(Path.Combine(work, dir))
            ? Directory.EnumerateFiles(Path.Combine(work, dir), "*.o", SearchOption.AllDirectories)
                .Select(p => Path.GetRelativePath(work, p).Replace('\\', '/'))
                .OrderBy(p => p, StringComparer.Ordinal).ToList()
            : new List<string>();

    private static HashSet<string> DefinedText(string nm, string work, IEnumerable<string> objects)
    {
        var names = new HashSet<string>();
        var list = objects.ToList();
        if (list.Count == 0)
        {
            return names;
        }
        var output = Proc.RunChecked("Reading symbols", nm, new[] { "--defined-only" }.Concat(list), work);
        foreach (var line in output.Split('\n'))
        {
            var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 3 && parts[^2] == "T")
            {
                names.Add(parts[^1].Trim());
            }
        }
        return names;
    }

    public static void Link(BuildPlan plan, string work, string outputName, string objcopy, string nm,
        Func<IEnumerable<string>, List<string>> expand)
    {
        Log.Info($"  linking {outputName}");
        var retroObjects = RetroDirs.SelectMany(d => Objects(work, d))
            .Where(o => !o.Contains("retro_rewind_product")).ToList();
        var baseObjects = Objects(work, "CMakeFiles/mkw_base_sensitive.dir")
            .Concat(Objects(work, "CMakeFiles/WiiCompiled.dir")
                .Where(o => !o.EndsWith("base_product.cpp.o") && !o.EndsWith("combined_product.cpp.o")))
            .ToList();
        const string combinedProduct = "CMakeFiles/mkw_combined_product.dir/src/product/combined_product.cpp.o";
        if (!File.Exists(Path.Combine(work, combinedProduct)))
        {
            throw new BuildException("The toolkit is missing combined_product.cpp.o.");
        }

        var baseSyms = DefinedText(nm, work, baseObjects);
        var retroSyms = DefinedText(nm, work, retroObjects);
        var colliding = baseSyms.Intersect(retroSyms).Where(s => s.StartsWith("func_", StringComparison.Ordinal))
            .OrderBy(s => s, StringComparer.Ordinal).ToList();
        Log.Detail($"[combined-lib] {colliding.Count} colliding symbols");

        var renameDir = Path.Combine(work, "combined_retro_renamed");
        Directory.CreateDirectory(renameDir);
        var map = Path.Combine(renameDir, "rename_map.txt");
        File.WriteAllLines(map, colliding.Select(n => $"{n} {n}__rr"));

        var renamed = new List<string>();
        var index = 0;
        foreach (var obj in retroObjects)
        {
            var target = $"combined_retro_renamed/{index++:D5}.o";
            Proc.RunChecked("Renaming Retro Rewind symbols", objcopy, new[] { $"--redefine-syms={map}", obj, target }, work);
            renamed.Add(target);
        }
        var still = baseSyms.Intersect(DefinedText(nm, work, renamed)).ToList();
        if (still.Count > 0)
        {
            throw new BuildException($"{still.Count} symbols still collide after renaming (e.g. {still[0]}).");
        }

        var retroLink = plan.Steps.FirstOrDefault(s => s.Kind == "link" && s.Output.EndsWith("libRetroRewind.so"))
            ?? throw new BuildException("The toolkit has no Retro Rewind link step.");
        var tokens = expand(retroLink.Args);
        var kept = tokens.Where(t => !IsRetroExclusive(t)).ToList();
        var insert = kept.FindIndex(t => t.EndsWith(".a") || t.StartsWith("-l") || t == "-Wl,--start-group");
        if (insert < 0)
        {
            insert = kept.Count;
        }
        kept.InsertRange(insert, renamed.Concat(baseObjects).Append(combinedProduct));

        var final = new List<string>();
        for (int i = 0; i < kept.Count; i++)
        {
            if (kept[i] == "-Wl,-soname,libRetroRewind.so")
            {
                final.Add($"-Wl,-soname,{outputName}");
            }
            else if (kept[i] == "-o" && i + 1 < kept.Count && kept[i + 1].EndsWith("libRetroRewind.so"))
            {
                final.Add("-o");
                final.Add(outputName);
                i++;
            }
            else
            {
                final.Add(kept[i]);
            }
        }
        var compiler = final[0];
        if (OperatingSystem.IsWindows() && !compiler.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(compiler + ".exe"))
        {
            compiler += ".exe";
        }
        Proc.RunChecked($"Linking {outputName}", compiler, final.Skip(1), work);
    }
}
