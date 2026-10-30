using System.Buffers.Binary;
using System.Security.Cryptography;

namespace DriftDroidBuilder;

/// <summary>
/// Reads main.dol and StaticR.rel out of a PAL Mario Kart Wii disc image (ISO, or WBFS including
/// split .wbfs/.wbf1 files). A port of runtime/src/hle/storage/wii_disc_extractor.cpp, which the
/// phone and Switch importers use: the public Wii disc format, with the long-public retail
/// common key Dolphin also embeds.
/// </summary>
public sealed class WiiDisc : IDisposable
{
    private static readonly byte[] CommonKey =
    {
        0xeb, 0xe4, 0x2a, 0x22, 0x5e, 0x85, 0x93, 0xe4, 0x48, 0xd9, 0xc5, 0x45, 0x73, 0x81, 0xaa, 0xf7,
    };

    private const long ClusterSize = 0x8000;
    private const long ClusterHashSize = 0x400;
    private const long ClusterDataSize = ClusterSize - ClusterHashSize;

    private readonly ISource _source;
    private readonly byte[] _titleKey = new byte[16];
    private readonly long _dataOffset;
    private long _loadedCluster = -1;
    private readonly byte[] _cluster = new byte[ClusterDataSize];

    public WiiDisc(string path)
    {
        var parts = FindParts(path);
        var magic = new byte[4];
        var raw = new SplitFile(parts);
        raw.ReadAt(0, magic);
        _source = magic.AsSpan().SequenceEqual("WBFS"u8) ? new WbfsSource(raw) : raw;

        var gameId = new byte[6];
        _source.ReadAt(0, gameId);
        var id = System.Text.Encoding.ASCII.GetString(gameId);
        if (id != "RMCP01")
        {
            throw new BuildException($"This disc's game ID is {id}, not RMCP01. Only the PAL (European) Mario Kart Wii disc works.");
        }

        var partition = FindDataPartition();
        var ticket = new byte[0x2a4];
        _source.ReadAt(partition, ticket);
        if (ticket[0x1f1] != 0)
        {
            throw new BuildException("This disc uses a Korean/vWii key. Only the standard PAL retail disc works.");
        }
        var iv = new byte[16];
        Array.Copy(ticket, 0x1dc, iv, 0, 8);
        using (var aes = Aes.Create())
        {
            aes.Key = CommonKey;
            aes.DecryptCbc(ticket.AsSpan(0x1bf, 16), iv, _titleKey, PaddingMode.None);
        }
        var header = new byte[0x1c];
        _source.ReadAt(partition + 0x2a4, header);
        _dataOffset = partition + ((long)BE32(header, 0x14) << 2);
    }

    public void Dispose() => (_source as IDisposable)?.Dispose();

    public byte[] ReadMainDol()
    {
        var boot = Read(0, 0x440);
        long dolOffset = (long)BE32(boot, 0x420) << 2;
        var dolHeader = Read(dolOffset, 0x100);
        uint end = 0x100;
        for (int i = 0; i < 18; i++)
        {
            uint offset = BE32(dolHeader, i * 4);
            uint size = BE32(dolHeader, 0x90 + i * 4);
            if (offset != 0)
            {
                end = Math.Max(end, offset + size);
            }
        }
        return Read(dolOffset, (int)end);
    }

    /// <summary>A file from the disc's file system, by path such as "rel/StaticR.rel".</summary>
    public byte[] ReadFile(string path)
    {
        var boot = Read(0, 0x440);
        long fstOffset = (long)BE32(boot, 0x424) << 2;
        long fstSize = (long)BE32(boot, 0x428) << 2;
        if (fstOffset == 0 || fstSize < 12 || fstSize > 256L << 20)
        {
            throw new BuildException("The disc's file table looks damaged.");
        }
        var fst = Read(fstOffset, (int)fstSize);
        uint count = BE32(fst, 8);
        int strings = (int)count * 12;

        string Name(uint index)
        {
            int nameOffset = strings + (int)(BE32(fst, (int)index * 12) & 0x00ffffff);
            int stop = Array.IndexOf(fst, (byte)0, nameOffset);
            return System.Text.Encoding.ASCII.GetString(fst, nameOffset, (stop < 0 ? fst.Length : stop) - nameOffset);
        }

        var wanted = path.Split('/');
        uint start = 1, endIndex = count;
        for (int level = 0; level < wanted.Length; level++)
        {
            bool found = false;
            for (uint i = start; i < endIndex;)
            {
                bool isDir = fst[i * 12] != 0;
                uint p1 = BE32(fst, (int)i * 12 + 4);
                uint p2 = BE32(fst, (int)i * 12 + 8);
                if (string.Equals(Name(i), wanted[level], StringComparison.OrdinalIgnoreCase))
                {
                    if (level == wanted.Length - 1 && !isDir)
                    {
                        return Read((long)p1 << 2, (int)p2);
                    }
                    if (isDir)
                    {
                        start = i + 1;
                        endIndex = p2;
                        found = true;
                        break;
                    }
                }
                i = isDir ? p2 : i + 1;
            }
            if (!found)
            {
                break;
            }
        }
        throw new BuildException($"{path} is missing from the disc.");
    }

    private long FindDataPartition()
    {
        var table = new byte[32];
        _source.ReadAt(0x40000, table);
        for (int group = 0; group < 4; group++)
        {
            uint count = BE32(table, group * 8);
            long tableOffset = (long)BE32(table, group * 8 + 4) << 2;
            if (count == 0)
            {
                continue;
            }
            var entries = new byte[count * 8];
            _source.ReadAt(tableOffset, entries);
            for (int i = 0; i < count; i++)
            {
                if (BE32(entries, i * 8 + 4) == 0)
                {
                    return (long)BE32(entries, i * 8) << 2;
                }
            }
        }
        throw new BuildException("No game partition found - this does not look like a Wii disc image.");
    }

    private byte[] Read(long offset, int size)
    {
        var result = new byte[size];
        int done = 0;
        while (done < size)
        {
            long logical = offset + done;
            long cluster = logical / ClusterDataSize;
            int within = (int)(logical % ClusterDataSize);
            int chunk = (int)Math.Min(size - done, ClusterDataSize - within);
            LoadCluster(cluster);
            Array.Copy(_cluster, within, result, done, chunk);
            done += chunk;
        }
        return result;
    }

    private void LoadCluster(long cluster)
    {
        if (cluster == _loadedCluster)
        {
            return;
        }
        var raw = new byte[ClusterSize];
        _source.ReadAt(_dataOffset + cluster * ClusterSize, raw);
        var iv = raw.AsSpan(0x3d0, 16).ToArray();
        using var aes = Aes.Create();
        aes.Key = _titleKey;
        aes.DecryptCbc(raw.AsSpan((int)ClusterHashSize, (int)ClusterDataSize), iv, _cluster, PaddingMode.None);
        _loadedCluster = cluster;
    }

    private static uint BE32(byte[] data, int offset) => BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(offset, 4));

    /// <summary>game.wbfs plus game.wbf1, game.wbf2... when a backup tool split the image.</summary>
    private static List<string> FindParts(string path)
    {
        var parts = new List<string> { path };
        if (path.EndsWith(".wbfs", StringComparison.OrdinalIgnoreCase))
        {
            var stem = path[..^5];
            for (int i = 1; ; i++)
            {
                var next = $"{stem}.wbf{i}";
                if (!File.Exists(next))
                {
                    break;
                }
                parts.Add(next);
            }
        }
        return parts;
    }

    private interface ISource
    {
        void ReadAt(long offset, Span<byte> buffer);
    }

    private sealed class SplitFile : ISource, IDisposable
    {
        private readonly List<FileStream> _files;
        private readonly List<long> _sizes;

        public SplitFile(List<string> paths)
        {
            _files = paths.Select(p => new FileStream(p, FileMode.Open, FileAccess.Read, FileShare.Read)).ToList();
            _sizes = _files.Select(f => f.Length).ToList();
        }

        public void ReadAt(long offset, Span<byte> buffer)
        {
            int done = 0;
            while (done < buffer.Length)
            {
                long pos = offset + done;
                int part = 0;
                while (part < _files.Count && pos >= _sizes[part])
                {
                    pos -= _sizes[part];
                    part++;
                }
                if (part == _files.Count)
                {
                    throw new BuildException("The disc image ends too early - the file may be incomplete.");
                }
                var file = _files[part];
                file.Seek(pos, SeekOrigin.Begin);
                int n = file.Read(buffer[done..(int)Math.Min(buffer.Length, done + (_sizes[part] - pos))]);
                if (n <= 0)
                {
                    throw new BuildException("The disc image ends too early - the file may be incomplete.");
                }
                done += n;
            }
        }

        public void Dispose()
        {
            foreach (var f in _files)
            {
                f.Dispose();
            }
        }
    }

    private sealed class WbfsSource : ISource, IDisposable
    {
        private readonly SplitFile _file;
        private readonly long _sectorSize;
        private readonly ushort[] _table;

        public WbfsSource(SplitFile file)
        {
            _file = file;
            var header = new byte[12];
            file.ReadAt(0, header);
            long hdSectorSize = 1L << header[8];
            _sectorSize = 1L << header[9];
            if (_sectorSize < 0x8000 || _sectorSize > 1L << 30)
            {
                throw new BuildException("This WBFS file has an implausible sector size.");
            }
            long maxDisc = 143432L * 2 * 0x8000;
            long entries = (maxDisc + _sectorSize - 1) / _sectorSize;
            var raw = new byte[entries * 2];
            file.ReadAt(hdSectorSize + 0x100, raw);
            _table = new ushort[entries];
            for (int i = 0; i < entries; i++)
            {
                _table[i] = BinaryPrimitives.ReadUInt16BigEndian(raw.AsSpan(i * 2, 2));
            }
        }

        public void ReadAt(long offset, Span<byte> buffer)
        {
            int done = 0;
            while (done < buffer.Length)
            {
                long logical = offset + done;
                long sector = logical / _sectorSize;
                long within = logical % _sectorSize;
                int chunk = (int)Math.Min(buffer.Length - done, _sectorSize - within);
                if (sector >= _table.Length)
                {
                    throw new BuildException("Read past the end of the WBFS block table.");
                }
                if (_table[sector] == 0)
                {
                    buffer.Slice(done, chunk).Clear();
                }
                else
                {
                    _file.ReadAt(_table[sector] * _sectorSize + within, buffer.Slice(done, chunk));
                }
                done += chunk;
            }
        }

        public void Dispose() => _file.Dispose();
    }
}
