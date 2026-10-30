using System.IO.Compression;

namespace DriftDroidBuilder;

public static class Toolchains
{
    // Android NDK r27c (27.2.12479018): the exact compiler release the DriftDroid app is built
    // with, from Google's own download server.
    // The SHA-256s were computed from Google's files; their published SHA-1s (repository2-3.xml)
    // matched.
    private const string NdkWindowsUrl = "https://dl.google.com/android/repository/android-ndk-r27c-windows.zip";
    public const string NdkWindowsSha256 = "27e49f11e0cee5800983d8af8f4acd5bf09987aa6f790d4439dda9f3643d2494";
    private const string NdkLinuxUrl = "https://dl.google.com/android/repository/android-ndk-r27c-linux.zip";
    public const string NdkLinuxSha256 = "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc";
    private const string NdkRelease = "android-ndk-r27c";

    /// <summary>The NDK's LLVM folder (toolchains/llvm/prebuilt/HOST), downloading the NDK the
    /// first time on Windows and Linux. DRIFTDROID_NDK can point at an installed NDK instead.</summary>
    public static async Task<string> AndroidAsync(string cache)
    {
        var configured = Environment.GetEnvironmentVariable("DRIFTDROID_NDK");
        if (!string.IsNullOrEmpty(configured))
        {
            return LlvmFolder(configured);
        }
        if (!OperatingSystem.IsWindows() && !OperatingSystem.IsLinux())
        {
            throw new BuildException("Set DRIFTDROID_NDK to an Android NDK r27c folder (on Windows and Linux the builder downloads it).");
        }
        var (url, sha256, host) = OperatingSystem.IsWindows()
            ? (NdkWindowsUrl, NdkWindowsSha256, "windows")
            : (NdkLinuxUrl, NdkLinuxSha256, "linux");
        var root = Path.Combine(cache, NdkRelease);
        var marker = Path.Combine(root, ".complete");
        if (!File.Exists(marker))
        {
            var zip = Path.Combine(cache, $"{NdkRelease}-{host}.zip");
            await Files.DownloadAsync(url, zip, sha256, "Android NDK (compiler, about 700 MB)");
            Log.Info("  unpacking the compiler...");
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
            // Only the LLVM toolchain is needed, not the NDK's build systems and samples.
            const string keep = NdkRelease + "/toolchains/llvm/prebuilt/";
            using (var archive = ZipFile.OpenRead(zip))
            {
                foreach (var entry in archive.Entries)
                {
                    if (!entry.FullName.StartsWith(keep, StringComparison.Ordinal) || entry.FullName.EndsWith('/'))
                    {
                        continue;
                    }
                    var dest = Path.Combine(cache, entry.FullName);
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    Extract(entry, dest);
                }
            }
            File.WriteAllText(marker, "ok");
            File.Delete(zip);
        }
        return LlvmFolder(root);
    }

    /// <summary>One zip entry, keeping what the Linux NDK needs: its symbolic links (clang++ is one)
    /// and the executable bits. Windows zips have neither.</summary>
    private static void Extract(ZipArchiveEntry entry, string dest)
    {
        var mode = (entry.ExternalAttributes >> 16) & 0xFFFF;
        if (!OperatingSystem.IsWindows() && (mode & 0xF000) == 0xA000)
        {
            using var reader = new StreamReader(entry.Open());
            var target = reader.ReadToEnd();
            if (File.Exists(dest) || Directory.Exists(dest))
            {
                File.Delete(dest);
            }
            File.CreateSymbolicLink(dest, target);
            return;
        }
        entry.ExtractToFile(dest, overwrite: true);
        if (!OperatingSystem.IsWindows() && (mode & 0x1FF) != 0)
        {
            File.SetUnixFileMode(dest, (UnixFileMode)(mode & 0x1FF));
        }
    }

    private static string LlvmFolder(string ndk)
    {
        var host = OperatingSystem.IsWindows() ? "windows-x86_64" : OperatingSystem.IsMacOS() ? "darwin-x86_64" : "linux-x86_64";
        var path = Path.Combine(ndk, "toolchains", "llvm", "prebuilt", host);
        if (!Directory.Exists(path))
        {
            throw new BuildException($"No NDK compiler at {path}.");
        }
        return path;
    }

    /// <summary>The devkitPro folder, for Switch builds. devkitPro asks that its tools come from
    /// its own installer, so the builder uses an existing install rather than downloading it.</summary>
    public static string Switch()
    {
        var candidates = new List<string?>
        {
            Environment.GetEnvironmentVariable("DEVKITPRO"),
            OperatingSystem.IsWindows() ? @"C:\devkitPro" : "/opt/devkitpro",
        };
        foreach (var dir in candidates.Where(c => !string.IsNullOrEmpty(c)).Select(c => ToNativePath(c!)))
        {
            var gcc = Path.Combine(dir, "devkitA64", "bin", OperatingSystem.IsWindows() ? "aarch64-none-elf-g++.exe" : "aarch64-none-elf-g++");
            var elf2nro = Path.Combine(dir, "tools", "bin", OperatingSystem.IsWindows() ? "elf2nro.exe" : "elf2nro");
            if (File.Exists(gcc) && File.Exists(elf2nro))
            {
                return dir;
            }
        }
        throw new BuildException(
            "Switch builds need devkitPro's compiler, which the builder cannot download for you.\n" +
            "  1. Get the devkitPro installer: https://github.com/devkitPro/installer/releases\n" +
            "  2. Install it and tick \"Switch Development\".\n" +
            "  3. Run this builder again.");
    }

    /// <summary>devkitPro's installer sets DEVKITPRO to an MSYS path such as /opt/devkitpro.</summary>
    private static string ToNativePath(string path)
    {
        if (OperatingSystem.IsWindows() && path.StartsWith("/opt/devkitpro", StringComparison.Ordinal))
        {
            return @"C:\devkitPro" + path["/opt/devkitpro".Length..].Replace('/', '\\');
        }
        return path;
    }
}
