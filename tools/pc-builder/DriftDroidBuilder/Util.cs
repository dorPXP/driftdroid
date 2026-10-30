using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace DriftDroidBuilder;

/// <summary>A problem the player can act on; printed as-is, without a stack trace.</summary>
public sealed class BuildException(string message) : Exception(message);

public static class Log
{
    private static readonly object Gate = new();
    private static StreamWriter? _file;
    private static bool _progressShown;

    /// <summary>False in the window version: there the lines go to <see cref="Line"/> only.</summary>
    public static bool ToConsole { get; set; } = true;

    /// <summary>Every line the player would see (text, is it a step heading).</summary>
    public static event Action<string, bool>? Line;

    /// <summary>A new stage of the build has started.</summary>
    public static event Action<Stage>? StageStarted;

    /// <summary>What the current stage is doing, with how far it is (0..1) when that is known.</summary>
    public static event Action<string, double?>? ProgressChanged;

    public static void OpenFile(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        _file?.Dispose();
        _file = new StreamWriter(path, append: false, Encoding.UTF8) { AutoFlush = true };
    }

    public static void Info(string text)
    {
        lock (Gate)
        {
            if (ToConsole)
            {
                if (_progressShown)
                {
                    Console.WriteLine();
                    _progressShown = false;
                }
                Console.WriteLine(text);
            }
            _file?.WriteLine(text);
        }
        Line?.Invoke(text, false);
    }

    /// <summary>Only in the log file: compiler output and command lines.</summary>
    public static void Detail(string text)
    {
        lock (Gate)
        {
            _file?.WriteLine(text);
        }
    }

    public static void Step(Stage stage, string text)
    {
        lock (Gate)
        {
            if (ToConsole)
            {
                Console.WriteLine($"\n== {text}");
                _progressShown = false;
            }
            _file?.WriteLine($"\n== {text}");
        }
        StageStarted?.Invoke(stage);
        Line?.Invoke(text, true);
    }

    /// <summary>A progress line that replaces itself on the console; not written to the log file.</summary>
    public static void Progress(string text, double? fraction)
    {
        if (ToConsole)
        {
            lock (Gate)
            {
                var percent = fraction is { } f ? $" ({f * 100:F0}%)" : "";
                Console.Write($"\r  {text}{percent}      ");
                _progressShown = true;
            }
        }
        ProgressChanged?.Invoke(text, fraction);
    }
}

/// <summary>The parts of a build, in order; the window shows them as a checklist.</summary>
public enum Stage
{
    Prepare,
    Disc,
    RetroRewind,
    Translate,
    Android,
    Switch,
    Done,
}

/// <summary>Stopping a build: the window's Stop button cancels this, and every running compiler
/// or translator is ended with it.</summary>
public static class Cancel
{
    private static CancellationTokenSource _source = new();

    public static CancellationToken Token => _source.Token;

    public static void Reset() => _source = new CancellationTokenSource();

    public static void Request() => _source.Cancel();

    public static void ThrowIfRequested() => _source.Token.ThrowIfCancellationRequested();
}

public static class Proc
{
    public sealed record Result(int ExitCode, string Output);

    private const string Nice = "/usr/bin/nice";
    private const string Ionice = "/usr/bin/ionice";

    /// <summary>Runs a program with an argument list (no shell), collecting its output.</summary>
    public static Result Run(string program, IEnumerable<string> args, string? workingDirectory = null,
        IDictionary<string, string>? environment = null)
    {
        var argList = args.ToList();
        // Windows limits a command line to 32767 characters; long link and archive lines go
        // through a response file, which clang, lld, llvm-ar and gcc all understand.
        string? responseFile = null;
        if (OperatingSystem.IsWindows() && argList.Sum(a => a.Length + 3) > 8000)
        {
            responseFile = Path.Combine(Path.GetTempPath(), $"ddb-{Guid.NewGuid():N}.rsp");
            File.WriteAllLines(responseFile, argList.Select(QuoteForResponseFile));
            argList = new List<string> { "@" + responseFile };
        }

        Cancel.ThrowIfRequested();
        // Compilers and the translator run at the lowest priority, so the computer stays usable
        // (music, browsing) during a build: they only get the processor time nothing else wants.
        // Linux sets niceness per thread, so a running process cannot be lowered as a whole from
        // outside: it is started through nice/ionice instead.
        if (OperatingSystem.IsLinux() && File.Exists(Nice))
        {
            var wrapped = new List<string>();
            if (File.Exists(Ionice))
            {
                wrapped.AddRange(new[] { "-c", "3", Nice });
            }
            wrapped.AddRange(new[] { "-n", "19", program });
            wrapped.AddRange(argList);
            argList = wrapped;
            program = File.Exists(Ionice) ? Ionice : Nice;
        }
        var start = new ProcessStartInfo(program)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = workingDirectory ?? Environment.CurrentDirectory,
        };
        foreach (var a in argList)
        {
            start.ArgumentList.Add(a);
        }
        if (environment != null)
        {
            foreach (var (k, v) in environment)
            {
                start.Environment[k] = v;
            }
        }

        var output = new StringBuilder();
        using var process = new Process { StartInfo = start };
        process.OutputDataReceived += (_, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
        try
        {
            process.Start();
            if (OperatingSystem.IsWindows())
            {
                // Inherited by the programs it starts in turn.
                try { process.PriorityClass = ProcessPriorityClass.BelowNormal; } catch (InvalidOperationException) { }
            }
        }
        catch (Exception e)
        {
            throw new BuildException($"Could not start {program}: {e.Message}");
        }
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        using (Cancel.Token.Register(() =>
               {
                   try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
               }))
        {
            process.WaitForExit();
        }
        if (responseFile != null)
        {
            try { File.Delete(responseFile); } catch (IOException) { }
        }
        Cancel.ThrowIfRequested();
        return new Result(process.ExitCode, output.ToString());
    }

    public static string RunChecked(string what, string program, IEnumerable<string> args, string? workingDirectory = null,
        IDictionary<string, string>? environment = null)
    {
        var list = args.ToList();
        Log.Detail($"$ {program} {string.Join(' ', list)}");
        var result = Run(program, list, workingDirectory, environment);
        Log.Detail(result.Output);
        if (result.ExitCode != 0)
        {
            var tail = string.Join('\n', result.Output.Split('\n').TakeLast(25));
            throw new BuildException($"{what} failed (exit code {result.ExitCode}).\n{tail}");
        }
        return result.Output;
    }

    private static string QuoteForResponseFile(string arg) =>
        arg.IndexOfAny(new[] { ' ', '\t', '"', '\\' }) < 0 ? arg : "\"" + arg.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
}

public static class Files
{
    public static string Sha256(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    public static string Sha256(byte[] data) => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    public static void CopyDirectory(string from, string to)
    {
        foreach (var dir in Directory.EnumerateDirectories(from, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(Path.Combine(to, Path.GetRelativePath(from, dir)));
        }
        Directory.CreateDirectory(to);
        foreach (var file in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories))
        {
            var dest = Path.Combine(to, Path.GetRelativePath(from, file));
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            File.Copy(file, dest, overwrite: true);
        }
    }

    /// <summary>Downloads to <paramref name="destination"/>, resuming a partial file, and checks
    /// the SHA-256 when one is given. Already-complete files are not fetched again.</summary>
    public static async Task DownloadAsync(string url, string destination, string? sha256, string label)
    {
        if (File.Exists(destination) && (sha256 == null || Sha256(destination) == sha256))
        {
            return;
        }
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var partial = destination + ".partial";
        using var http = new HttpClient { Timeout = TimeSpan.FromHours(3) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("DriftDroidBuilder/1.4");
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                long have = File.Exists(partial) ? new FileInfo(partial).Length : 0;
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                if (have > 0)
                {
                    request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
                }
                using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, Cancel.Token);
                if (have > 0 && response.StatusCode != System.Net.HttpStatusCode.PartialContent)
                {
                    have = 0; // the server ignored the range: start over
                }
                response.EnsureSuccessStatusCode();
                var total = (response.Content.Headers.ContentLength ?? 0) + have;
                await using var source = await response.Content.ReadAsStreamAsync(Cancel.Token);
                await using var target = new FileStream(partial, have > 0 ? FileMode.Append : FileMode.Create);
                var buffer = new byte[1 << 20];
                var lastReport = DateTime.MinValue;
                int read;
                while ((read = await source.ReadAsync(buffer, Cancel.Token)) > 0)
                {
                    await target.WriteAsync(buffer.AsMemory(0, read));
                    have += read;
                    if (DateTime.UtcNow - lastReport > TimeSpan.FromSeconds(1))
                    {
                        lastReport = DateTime.UtcNow;
                        Log.Progress($"Downloading {label}: {have / (1 << 20)} MB", total > 0 ? (double)have / total : null);
                    }
                }
                Log.Info($"  {label}: {have / (1 << 20)} MB, done.");
                break;
            }
            catch (Exception e) when ((e is HttpRequestException or IOException or TaskCanceledException) && !Cancel.Token.IsCancellationRequested)
            {
                if (attempt >= 6)
                {
                    throw new BuildException($"Downloading {label} failed: {e.Message}. Check the internet connection and run the builder again; it continues where it stopped.");
                }
                Log.Info($"\n  {label}: connection problem ({e.Message}), trying again...");
                await Task.Delay(TimeSpan.FromSeconds(5 * attempt), Cancel.Token);
            }
        }
        if (sha256 != null)
        {
            var actual = Sha256(partial);
            if (actual != sha256)
            {
                File.Delete(partial);
                throw new BuildException($"The download of {label} is damaged (checksum {actual}). Run the builder again to download it afresh.");
            }
        }
        File.Move(partial, destination, overwrite: true);
    }
}
