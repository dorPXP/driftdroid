using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace DriftDroidBuilder;

/// <summary>
/// Translations for the builder's window. The same JSON files as the game and the app (i18n/&lt;code&gt;.json, copied
/// into Assets/i18n by tools/i18n/gen.py and embedded): the English text of a string is its key, and a missing entry
/// shows the English text. The choice is a small file next to the other builder settings.
/// </summary>
public static class I18n
{
    private static Dictionary<string, string> _table = new();
    private static List<(string Code, string Name)>? _languages;

    public static string Code { get; private set; } = "en";

    /// <summary>The translation of an English string (the same text when there is none).</summary>
    public static string T(string text) => _table.TryGetValue(text, out var translated) ? translated : text;

    public static IReadOnlyList<(string Code, string Name)> Languages
    {
        get
        {
            if (_languages != null)
            {
                return _languages;
            }
            var order = new[] { "en", "es-419", "es-ES", "pt-BR", "fr", "de", "it", "ja", "ko", "zh-Hans", "ru", "tr", "nl", "pl" };
            var found = new Dictionary<string, string>();
            var assembly = Assembly.GetExecutingAssembly();
            foreach (var resource in assembly.GetManifestResourceNames())
            {
                if (!resource.StartsWith("i18n/", StringComparison.Ordinal) || !resource.EndsWith(".json", StringComparison.Ordinal))
                {
                    continue;
                }
                var code = resource["i18n/".Length..^".json".Length];
                try
                {
                    using var stream = assembly.GetManifestResourceStream(resource)!;
                    using var doc = JsonDocument.Parse(stream);
                    found[code] = doc.RootElement.TryGetProperty("name", out var name) ? name.GetString() ?? code : code;
                }
                catch (Exception)
                {
                    found[code] = code;
                }
            }
            _languages = order.Where(found.ContainsKey).Concat(found.Keys.Where(k => !order.Contains(k)).OrderBy(k => k))
                .Select(code => (code, found[code])).ToList();
            return _languages;
        }
    }

    /// <summary>Loads the saved language, or the computer's own ("auto").</summary>
    public static void Init()
    {
        var choice = SavedChoice();
        var code = choice == "auto" || !Languages.Any(l => l.Code == choice) ? SystemLanguage() : choice;
        Load(code);
    }

    public static string SavedChoice()
    {
        try
        {
            var text = File.ReadAllText(ChoicePath()).Trim();
            return text.Length > 0 ? text : "auto";
        }
        catch (Exception)
        {
            return "auto";
        }
    }

    public static void SaveChoice(string choice)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ChoicePath())!);
            File.WriteAllText(ChoicePath(), choice + "\n");
        }
        catch (Exception)
        {
            // not being able to remember the choice is not worth stopping for
        }
    }

    private static string ChoicePath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DriftDroidBuilder", "language.cfg");

    private static void Load(string code)
    {
        Code = code;
        _table = new Dictionary<string, string>();
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream($"i18n/{code}.json");
        if (stream == null)
        {
            return;
        }
        try
        {
            using var doc = JsonDocument.Parse(stream);
            if (doc.RootElement.TryGetProperty("strings", out var strings))
            {
                foreach (var entry in strings.EnumerateObject())
                {
                    _table[entry.Name] = entry.Value.GetString() ?? entry.Name;
                }
            }
        }
        catch (Exception)
        {
            _table = new Dictionary<string, string>();
        }
    }

    /// <summary>The computer's language as one of our codes ("en" when it has none).</summary>
    public static string SystemLanguage()
    {
        var tag = "";
        if (OperatingSystem.IsWindows())
        {
            var buffer = new StringBuilder(85);
            tag = GetUserDefaultLocaleName(buffer, buffer.Capacity) > 0 ? buffer.ToString() : "";
        }
        else
        {
            foreach (var name in new[] { "LC_ALL", "LC_MESSAGES", "LANG" })
            {
                var value = Environment.GetEnvironmentVariable(name);
                if (!string.IsNullOrEmpty(value))
                {
                    tag = value;
                    break;
                }
            }
        }
        tag = tag.Replace('_', '-').Split('.')[0].ToLowerInvariant();
        string match =
            tag.StartsWith("es-es") ? "es-ES" :
            tag.StartsWith("es") ? "es-419" :
            tag.StartsWith("pt") ? "pt-BR" :
            tag.StartsWith("zh") ? (tag.Contains("hant") || tag.Contains("-tw") || tag.Contains("-hk") ? "en" : "zh-Hans") :
            new[] { "en", "fr", "de", "it", "ja", "ko", "ru", "tr", "nl", "pl" }.FirstOrDefault(c => tag.StartsWith(c)) ?? "en";
        return Languages.Any(l => l.Code == match) ? match : "en";
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetUserDefaultLocaleName(StringBuilder lpLocaleName, int cchLocaleName);
}
