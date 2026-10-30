using System.Collections.Concurrent;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DriftDroidBuilder;

/// <summary>plan.json, written by tools/pc-builder/extract_plan.py from a real build.</summary>
public sealed class BuildPlan
{
    [JsonPropertyName("platform")] public string Platform { get; set; } = "";
    [JsonPropertyName("groups")] public Dictionary<string, CompileGroup> Groups { get; set; } = new();
    [JsonPropertyName("steps")] public List<Step> Steps { get; set; } = new();
    [JsonPropertyName("headers")] public List<string> Headers { get; set; } = new();
    [JsonPropertyName("env")] public Dictionary<string, string> Env { get; set; } = new();

    public sealed class CompileGroup
    {
        [JsonPropertyName("sourceDir")] public string SourceDir { get; set; } = "";
        [JsonPropertyName("objectDir")] public string ObjectDir { get; set; } = "";
        [JsonPropertyName("args")] public List<string> Args { get; set; } = new();
        [JsonPropertyName("files")] public List<string> Files { get; set; } = new();
    }

    public sealed class Step
    {
        [JsonPropertyName("kind")] public string Kind { get; set; } = "";
        [JsonPropertyName("cwd")] public string Cwd { get; set; } = "";
        [JsonPropertyName("args")] public List<string> Args { get; set; } = new();
        [JsonPropertyName("output")] public string Output { get; set; } = "";
    }

    public static BuildPlan Load(string path) =>
        JsonSerializer.Deserialize<BuildPlan>(File.ReadAllText(path)) ?? throw new BuildException($"{path} is empty.");
}

/// <summary>Compiles the game code and replays the recorded archive/link steps.</summary>
public sealed class PlanRunner
{
    private readonly BuildPlan _plan;
    private readonly string _repo;
    private readonly string _work;
    private readonly string _toolchain;
    private readonly string _pgo;
    private readonly Dictionary<string, List<string>> _groupObjects = new();

    /// <summary>Compile groups and steps left out of this build (the Retro Rewind ones when the
    /// player builds without it).</summary>
    public Func<string, bool> SkipGroup { get; init; } = _ => false;
    public Func<string, bool> SkipStep { get; init; } = _ => false;

    public PlanRunner(BuildPlan plan, string repo, string work, string toolchain, string pgo)
    {
        _plan = plan;
        _repo = repo;
        _work = work;
        _toolchain = toolchain;
        _pgo = pgo;
    }

    private Dictionary<string, string> Environment() =>
        _plan.Env.ToDictionary(kv => kv.Key, kv => Fill(kv.Value));

    private string Fill(string text) => text
        .Replace("{REPO}", _repo.Replace('\\', '/'))
        .Replace("{WORK}", _work.Replace('\\', '/'))
        .Replace("{TC}", _toolchain.Replace('\\', '/'))
        .Replace("{PGO}", _pgo.Replace('\\', '/'));

    /// <summary>A recorded program path, as an executable on this computer.</summary>
    private static string Program(string path)
    {
        if (OperatingSystem.IsWindows() && !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && !File.Exists(path))
        {
            var exe = path + ".exe";
            if (File.Exists(exe))
            {
                return exe;
            }
            // NDK and devkitPro ship some tools only as .cmd wrappers on Windows.
            if (File.Exists(path + ".cmd"))
            {
                return path + ".cmd";
            }
        }
        return path;
    }

    /// <summary>The source files of a group: every file the translator wrote into a shard folder,
    /// or the fixed list for folders that hold other files too.</summary>
    private List<string> GroupSources(BuildPlan.CompileGroup group, string id)
    {
        var dir = Path.Combine(_repo, group.SourceDir);
        if (group.SourceDir.StartsWith("generated/build_shards/", StringComparison.Ordinal))
        {
            var ext = "." + id[(id.LastIndexOf(':') + 1)..];
            if (!Directory.Exists(dir))
            {
                throw new BuildException($"The translator did not produce {group.SourceDir}.");
            }
            return Directory.EnumerateFiles(dir, "*" + ext).Select(Path.GetFileName).OfType<string>()
                .OrderBy(n => n, StringComparer.Ordinal).ToList();
        }
        foreach (var f in group.Files.Where(f => !File.Exists(Path.Combine(dir, f))))
        {
            throw new BuildException($"The translator did not produce {group.SourceDir}/{f}.");
        }
        return group.Files;
    }

    public void Compile(int jobs)
    {
        var work = new List<(string Program, List<string> Args, string Label)>();
        foreach (var (id, group) in _plan.Groups.Where(g => !SkipGroup(g.Key)))
        {
            var objects = new List<string>();
            foreach (var file in GroupSources(group, id))
            {
                var src = Path.Combine(_repo, group.SourceDir, file).Replace('\\', '/');
                var obj = $"{group.ObjectDir}/{file}.o";
                objects.Add(obj);
                Directory.CreateDirectory(Path.Combine(_work, group.ObjectDir));
                var args = group.Args.Select(a => a == "{SRC}" ? src : a == "{OBJ}" ? obj : Fill(a)).ToList();
                work.Add((Program(args[0]), args.Skip(1).ToList(), $"{group.SourceDir}/{file}"));
            }
            _groupObjects[id] = objects;
        }

        // Biggest first, so the long shards do not end up alone at the end.
        work = work.OrderByDescending(w => SourceSize(w.Label)).ToList();
        int done = 0;
        var errors = new ConcurrentBag<string>();
        var started = DateTime.UtcNow;
        Parallel.ForEach(work, new ParallelOptions { MaxDegreeOfParallelism = jobs }, item =>
        {
            if (!errors.IsEmpty || Cancel.Token.IsCancellationRequested)
            {
                return;
            }
            Proc.Result result;
            try
            {
                result = Proc.Run(item.Program, item.Args, _work, Environment());
            }
            catch (OperationCanceledException)
            {
                return;
            }
            Log.Detail($"$ {item.Program} {string.Join(' ', item.Args)}\n{result.Output}");
            if (result.ExitCode != 0)
            {
                errors.Add($"{item.Label}:\n{string.Join('\n', result.Output.Split('\n').Take(20))}");
                return;
            }
            var n = Interlocked.Increment(ref done);
            var minutes = (DateTime.UtcNow - started).TotalMinutes;
            Log.Progress($"Compiled {n} of {work.Count} files ({minutes:F0} min)", (double)n / work.Count);
        });
        Cancel.ThrowIfRequested();
        Log.Info($"  compiled {done} files in {(DateTime.UtcNow - started).TotalMinutes:F0} min");
        if (!errors.IsEmpty)
        {
            throw new BuildException("Compiling the game code failed:\n" + errors.First());
        }
    }

    private long SourceSize(string label)
    {
        var path = Path.Combine(_repo, label);
        return File.Exists(path) ? new FileInfo(path).Length : 0;
    }

    private List<string> Expand(IEnumerable<string> args)
    {
        var result = new List<string>();
        foreach (var a in args)
        {
            if (a.StartsWith("@group:", StringComparison.Ordinal))
            {
                result.AddRange(_groupObjects[a["@group:".Length..]]);
            }
            else
            {
                result.Add(Fill(a));
            }
        }
        return result;
    }

    public void RunSteps(string objcopy, string nm)
    {
        foreach (var step in _plan.Steps.Where(s => !SkipStep(s.Output)))
        {
            var cwd = string.IsNullOrEmpty(step.Cwd) ? _work : Path.Combine(_work, step.Cwd);
            switch (step.Kind)
            {
                case "archive":
                {
                    var output = Path.Combine(_work, step.Output);
                    if (File.Exists(output))
                    {
                        File.Delete(output);
                    }
                    Log.Info($"  archiving {step.Output}");
                    var args = Expand(step.Args);
                    Proc.RunChecked($"Archiving {step.Output}", Program(args[0]), args.Skip(1), cwd, Environment());
                    break;
                }
                case "ranlib":
                case "link":
                {
                    Log.Info($"  linking {step.Output}");
                    var args = Expand(step.Args);
                    Proc.RunChecked($"Linking {step.Output}", Program(args[0]), args.Skip(1), cwd, Environment());
                    break;
                }
                case "android-combined":
                    AndroidCombined.Link(_plan, _work, step.Output, Program(objcopy), Program(nm), Expand);
                    break;
                default:
                    throw new BuildException($"Unknown build step '{step.Kind}' - the builder and its toolkit do not match.");
            }
        }
    }
}
