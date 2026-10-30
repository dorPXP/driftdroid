namespace DriftDroidBuilder;

/// <summary>
/// The Switch .nro files: the linked .elf plus icon, title (NACP) and a RomFS holding the files the
/// app installs on its first start, the same layout the release NROs use.
/// </summary>
public static class SwitchPackaging
{
    private static readonly (string Elf, string Nro, string Nacp)[] Products =
    {
        ("WiiCompiled.elf", "DriftDroid.nro", "app.nacp"),
        ("RetroRewind.elf", "RetroRewind.nro", "app-retrorewind.nacp"),
    };

    public static List<string> MakeNros(string toolkit, string work, string dkp, string output, bool retroRewind)
    {
        var assets = Path.Combine(toolkit, "switch", "nro");
        var elf2nro = Path.Combine(dkp, "tools", "bin", OperatingSystem.IsWindows() ? "elf2nro.exe" : "elf2nro");
        var outDir = Path.Combine(output, "switch");
        Directory.CreateDirectory(outDir);
        // A RetroRewind.nro left from an earlier build would not match this one.
        File.Delete(Path.Combine(outDir, "RetroRewind.nro"));
        var made = new List<string>();
        foreach (var (elf, nro, nacp) in Products.Where(p => retroRewind || p.Nro != "RetroRewind.nro"))
        {
            var elfPath = Path.Combine(work, elf);
            if (!File.Exists(elfPath))
            {
                throw new BuildException($"{elf} was not built.");
            }
            var target = Path.Combine(outDir, nro);
            Proc.RunChecked($"Packing {nro}", elf2nro, new[]
            {
                elfPath, target,
                $"--icon={Path.Combine(assets, "icon.jpg")}",
                $"--nacp={Path.Combine(assets, nacp)}",
                $"--romfsdir={Path.Combine(assets, "romfs")}",
            }, work);
            made.Add(target);
        }
        return made;
    }
}
