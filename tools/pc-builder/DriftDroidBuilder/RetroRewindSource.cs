using System.Buffers.Binary;
using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;

namespace DriftDroidBuilder;

/// <summary>
/// Finds Retro Rewind's newest game logic (Binaries/Code.pul) on the mod's own update server, so a
/// new Retro Rewind release needs no new builder. The server lists every release, oldest first,
/// each with an update archive holding only what changed; the newest archive that carries Code.pul
/// has the current code. Only that one file is fetched (HTTP ranges), never a whole archive - some
/// update archives are hundreds of MB.
/// </summary>
public static class RetroRewindSource
{
    public const string VersionListUrl = "https://update.rwfc.net/RetroRewind/RetroRewindVersion.txt";
    private const string CodeEntry = "RetroRewind6/Binaries/Code.pul";

    /// <param name="Version">The Retro Rewind release the code is current for.</param>
    /// <param name="CodeVersion">The release whose update archive brought this Code.pul.</param>
    public sealed record Code(string Version, string CodeVersion, string Sha256);

    private sealed record Release(string Version, string Url);

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("DriftDroidBuilder/1.4");
        return http;
    }

    /// <summary>Writes the newest Code.pul (or the one of <paramref name="pinnedVersion"/>) to
    /// <paramref name="destination"/>. Fetched files are kept in <paramref name="cache"/>: a
    /// release's archive never changes once published.</summary>
    public static async Task<Code> FetchAsync(string? pinnedVersion, string destination, string cache)
    {
        var releases = await ReadReleasesAsync();
        var newest = releases.Count - 1;
        if (pinnedVersion != null)
        {
            newest = releases.FindLastIndex(r => r.Version == pinnedVersion);
            if (newest < 0)
            {
                throw new BuildException($"Retro Rewind {pinnedVersion} is not on the Retro Rewind update server.");
            }
        }
        var version = releases[newest].Version;
        Log.Info($"  newest Retro Rewind: {version}");

        for (var i = newest; i >= 0; i--)
        {
            var release = releases[i];
            var cached = Path.Combine(cache, $"RetroRewind-{release.Version}-Code.pul");
            byte[]? code = File.Exists(cached) ? await File.ReadAllBytesAsync(cached) : null;
            if (code == null)
            {
                code = await WithRetriesAsync($"Retro Rewind {release.Version}", () => ReadEntryAsync(release.Url, CodeEntry));
                if (code == null)
                {
                    Log.Detail($"  Retro Rewind {release.Version}: no game code in its update, looking at the one before");
                    continue;
                }
                Directory.CreateDirectory(cache);
                await File.WriteAllBytesAsync(cached, code);
            }
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            await File.WriteAllBytesAsync(destination, code);
            var sha = Files.Sha256(code);
            Log.Info($"  Retro Rewind game code from the {release.Version} update ({code.Length / 1024} KB, sha256 {sha[..16]}...)");
            return new Code(version, release.Version, sha);
        }
        throw new BuildException("No Retro Rewind update on the server holds the game code (Binaries/Code.pul).");
    }

    private static async Task<List<Release>> ReadReleasesAsync()
    {
        string text;
        try
        {
            text = await WithRetriesAsync("the Retro Rewind version list", () => Http.GetStringAsync(VersionListUrl));
        }
        catch (BuildException e)
        {
            throw new BuildException($"{e.Message}\nThe builder needs it to get Retro Rewind's game code.");
        }
        // "<version> <update archive url> <path> <folder>", oldest first.
        var releases = text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(line => line.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            .Where(f => f.Length >= 2 && f[1].StartsWith("https://", StringComparison.Ordinal))
            .Select(f => new Release(f[0], f[1]))
            .ToList();
        if (releases.Count == 0)
        {
            throw new BuildException($"The Retro Rewind version list ({VersionListUrl}) is empty or in a format the builder does not know.");
        }
        return releases;
    }

    private static async Task<T> WithRetriesAsync<T>(string label, Func<Task<T>> action)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await action();
            }
            catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException)
            {
                if (attempt >= 5)
                {
                    throw new BuildException($"Downloading {label} failed: {e.Message}. Check the internet connection and run the builder again.");
                }
                Log.Info($"  {label}: connection problem ({e.Message}), trying again...");
                await Task.Delay(TimeSpan.FromSeconds(5 * attempt), Cancel.Token);
            }
        }
    }

    // --- one file out of a remote zip -----------------------------------------------------------

    /// <summary>The uncompressed bytes of <paramref name="name"/> in the zip at <paramref name="url"/>,
    /// or null when the archive does not have it. Reads only the archive's directory and that entry.</summary>
    private static async Task<byte[]?> ReadEntryAsync(string url, string name)
    {
        // End of central directory: 22 bytes plus a comment of up to 64 KB, at the very end.
        var (tail, size) = await GetTailAsync(url, 22 + 0xFFFF + 20);
        var eocd = LastIndexOf(tail, 0x06054b50, tail.Length - 22);
        if (eocd < 0)
        {
            throw new IOException("the update archive is not a zip file");
        }
        long cdSize = BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(eocd + 12));
        long cdOffset = BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(eocd + 16));
        if ((cdSize == 0xFFFFFFFF || cdOffset == 0xFFFFFFFF) && eocd >= 20
            && BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(eocd - 20)) == 0x07064b50)
        {
            // Zip64: the real sizes are in the zip64 end record the locator points at.
            var record = (long)BinaryPrimitives.ReadUInt64LittleEndian(tail.AsSpan(eocd - 20 + 8));
            var z64 = await GetRangeAsync(url, record, 56);
            cdSize = (long)BinaryPrimitives.ReadUInt64LittleEndian(z64.AsSpan(40));
            cdOffset = (long)BinaryPrimitives.ReadUInt64LittleEndian(z64.AsSpan(48));
        }
        if (cdOffset + cdSize > size)
        {
            throw new IOException("the update archive's directory is damaged");
        }
        var cd = await GetRangeAsync(url, cdOffset, cdSize);

        for (var p = 0; p + 46 <= cd.Length && BinaryPrimitives.ReadUInt32LittleEndian(cd.AsSpan(p)) == 0x02014b50;)
        {
            var method = BinaryPrimitives.ReadUInt16LittleEndian(cd.AsSpan(p + 10));
            var crc = BinaryPrimitives.ReadUInt32LittleEndian(cd.AsSpan(p + 16));
            long compressed = BinaryPrimitives.ReadUInt32LittleEndian(cd.AsSpan(p + 20));
            long uncompressed = BinaryPrimitives.ReadUInt32LittleEndian(cd.AsSpan(p + 24));
            var nameLength = BinaryPrimitives.ReadUInt16LittleEndian(cd.AsSpan(p + 28));
            var extraLength = BinaryPrimitives.ReadUInt16LittleEndian(cd.AsSpan(p + 30));
            var commentLength = BinaryPrimitives.ReadUInt16LittleEndian(cd.AsSpan(p + 32));
            long local = BinaryPrimitives.ReadUInt32LittleEndian(cd.AsSpan(p + 42));
            var entryName = System.Text.Encoding.UTF8.GetString(cd, p + 46, nameLength);
            if (entryName == name)
            {
                ReadZip64Extra(cd.AsSpan(p + 46 + nameLength, extraLength), ref uncompressed, ref compressed, ref local);
                var header = await GetRangeAsync(url, local, 30);
                if (BinaryPrimitives.ReadUInt32LittleEndian(header) != 0x04034b50)
                {
                    throw new IOException("the update archive's file header is damaged");
                }
                var dataStart = local + 30 + BinaryPrimitives.ReadUInt16LittleEndian(header.AsSpan(26))
                                + BinaryPrimitives.ReadUInt16LittleEndian(header.AsSpan(28));
                var data = await GetRangeAsync(url, dataStart, compressed);
                var bytes = method switch
                {
                    0 => data,
                    8 => Inflate(data, uncompressed),
                    _ => throw new IOException($"the update archive uses a compression method the builder does not know ({method})"),
                };
                if (bytes.Length != uncompressed || Crc32(bytes) != crc)
                {
                    throw new IOException($"{name} arrived damaged");
                }
                return bytes;
            }
            p += 46 + nameLength + extraLength + commentLength;
        }
        return null;
    }

    private static void ReadZip64Extra(ReadOnlySpan<byte> extra, ref long uncompressed, ref long compressed, ref long local)
    {
        while (extra.Length >= 4)
        {
            var id = BinaryPrimitives.ReadUInt16LittleEndian(extra);
            var length = BinaryPrimitives.ReadUInt16LittleEndian(extra[2..]);
            var body = extra.Slice(4, Math.Min(length, extra.Length - 4));
            if (id == 0x0001)
            {
                // Present only for the fields that overflowed, in this order.
                var at = 0;
                if (uncompressed == 0xFFFFFFFF && at + 8 <= body.Length) { uncompressed = (long)BinaryPrimitives.ReadUInt64LittleEndian(body[at..]); at += 8; }
                if (compressed == 0xFFFFFFFF && at + 8 <= body.Length) { compressed = (long)BinaryPrimitives.ReadUInt64LittleEndian(body[at..]); at += 8; }
                if (local == 0xFFFFFFFF && at + 8 <= body.Length) { local = (long)BinaryPrimitives.ReadUInt64LittleEndian(body[at..]); }
                return;
            }
            extra = extra[Math.Min(extra.Length, 4 + length)..];
        }
    }

    private static byte[] Inflate(byte[] data, long size)
    {
        using var input = new DeflateStream(new MemoryStream(data), CompressionMode.Decompress);
        using var output = new MemoryStream((int)Math.Min(size, int.MaxValue));
        input.CopyTo(output);
        return output.ToArray();
    }

    private static async Task<(byte[] Bytes, long FileSize)> GetTailAsync(string url, int length)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(null, length);
        using var response = await Http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        var bytes = await response.Content.ReadAsByteArrayAsync();
        if (response.StatusCode == HttpStatusCode.PartialContent)
        {
            var total = response.Content.Headers.ContentRange?.Length
                        ?? throw new IOException("the update server sent no file size");
            return (bytes, total);
        }
        return (bytes, bytes.Length); // small file: the server sent all of it
    }

    private static async Task<byte[]> GetRangeAsync(string url, long offset, long length)
    {
        if (length == 0)
        {
            return Array.Empty<byte>();
        }
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(offset, offset + length - 1);
        using var response = await Http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        var bytes = await response.Content.ReadAsByteArrayAsync();
        if (response.StatusCode != HttpStatusCode.PartialContent)
        {
            // The server ignored the range and sent the whole file.
            if (bytes.LongLength < offset + length)
            {
                throw new IOException("the update archive is shorter than its directory says");
            }
            return bytes.AsSpan((int)offset, (int)length).ToArray();
        }
        if (bytes.LongLength != length)
        {
            throw new IOException("the update server sent a partial answer");
        }
        return bytes;
    }

    private static int LastIndexOf(byte[] data, uint signature, int from)
    {
        for (var i = from; i >= 0; i--)
        {
            if (BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(i)) == signature)
            {
                return i;
            }
        }
        return -1;
    }

    private static readonly uint[] CrcTable = Enumerable.Range(0, 256).Select(n =>
    {
        var c = (uint)n;
        for (var k = 0; k < 8; k++)
        {
            c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
        }
        return c;
    }).ToArray();

    private static uint Crc32(byte[] data)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var b in data)
        {
            crc = CrcTable[(crc ^ b) & 0xFF] ^ (crc >> 8);
        }
        return crc ^ 0xFFFFFFFFu;
    }
}
