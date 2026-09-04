// Extracts a Wii disc image into the DATA folder layout hle/storage/dvd.cpp mounts as its DVD
// root. Format details below (partition table, ticket/title-key crypto, per-cluster data
// decryption, boot.bin/FST layout) are the long-public Wii disc format - the same one every disc
// tool independently reimplements from the same public documentation (wiibrew.org's "Wii Disc"
// page; every offset here was additionally cross-checked against Dolphin's own public
// implementation before being written). The Wii common key below is likewise long-public (leaked
// in 2008) and is embedded the same way Dolphin's own public source does.
//
// Android-only: this backs the in-app SAF ROM picker (RomImportOverlay.kt), which is the only
// caller. Desktop still goes through the separate Launcher/DolphinTool.exe pipeline, and this
// file's use of raw POSIX pread() has no Windows equivalent anyway.
#if defined(__ANDROID__)

#include "hle/storage/wii_disc_extractor.h"

#include <cryptopp/aes.h>
#include <cryptopp/modes.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include <unistd.h>

namespace fs = std::filesystem;

namespace {

std::atomic<uint64_t> g_bytesDone{0};
std::atomic<uint64_t> g_bytesTotal{0};

// Retail common key (index 0). Korean/vWii discs use different keys and are rejected below
// rather than guessed at, since this project only supports PAL RMCP01 anyway.
constexpr uint8_t kCommonKey[16] = {
    0xeb, 0xe4, 0x2a, 0x22, 0x5e, 0x85, 0x93, 0xe4, 0x48, 0xd9, 0xc5, 0x45, 0x73, 0x81, 0xaa, 0xf7,
};

constexpr uint64_t kClusterSize = 0x8000;
constexpr uint64_t kClusterHashSize = 0x400;
constexpr uint64_t kClusterDataSize = kClusterSize - kClusterHashSize;  // 0x7c00

uint32_t ReadBE32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}

uint16_t ReadBE16(const uint8_t* p) { return (uint16_t(p[0]) << 8) | uint16_t(p[1]); }

class ExtractError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

void AesCbcDecrypt(const uint8_t key[16], const uint8_t iv[16], const uint8_t* in, uint8_t* out,
                    size_t size) {
    CryptoPP::CBC_Mode<CryptoPP::AES>::Decryption dec;
    dec.SetKeyWithIV(key, 16, iv);
    dec.ProcessData(out, in, size);
}

// ----------------------------------------------------------------------------------------------
// Container byte sources - present a uniform "read absolute disc bytes" interface regardless of
// whether the picked file is a plain disc image or a WBFS container.
// ----------------------------------------------------------------------------------------------

class ByteSource {
public:
    virtual ~ByteSource() = default;
    virtual void ReadAt(uint64_t offset, void* buffer, size_t size) = 0;  // throws ExtractError
};

void PreadExact(int fd, uint64_t offset, void* buffer, size_t size, const char* what) {
    auto* out = static_cast<uint8_t*>(buffer);
    size_t done = 0;
    while (done < size) {
        const ssize_t n = pread(fd, out + done, size - done, static_cast<off_t>(offset + done));
        if (n <= 0) {
            throw ExtractError(std::string("Unexpected end of file while reading ") + what + ".");
        }
        done += static_cast<size_t>(n);
    }
}

class RawIsoSource : public ByteSource {
public:
    explicit RawIsoSource(int fd) : fd_(fd) {}
    void ReadAt(uint64_t offset, void* buffer, size_t size) override {
        PreadExact(fd_, offset, buffer, size, "the disc image");
    }

private:
    int fd_;
};

// Single-file WBFS container: a small header + a bit table (unused here), then one WbfsDiscInfo
// slot (a copy of the disc header followed by a table mapping logical WBFS-sector index to
// physical WBFS-sector index on disk; 0 = unallocated/sparse), then the remapped sectors
// themselves.
class WbfsSource : public ByteSource {
public:
    explicit WbfsSource(int fd) : fd_(fd) {
        uint8_t header[12];
        PreadExact(fd_, 0, header, sizeof(header), "the WBFS header");
        if (std::memcmp(header, "WBFS", 4) != 0) {
            throw ExtractError("Not a WBFS file.");
        }
        const uint8_t hdSecSzShift = header[8];
        const uint8_t wbfsSecSzShift = header[9];
        const uint64_t hdSectorSize = uint64_t(1) << hdSecSzShift;
        wbfsSectorSize_ = hdSectorSize << wbfsSecSzShift;
        if (wbfsSectorSize_ == 0 || wbfsSectorSize_ > (uint64_t(1) << 30)) {
            throw ExtractError("This WBFS file has an implausible sector size.");
        }

        // Disc slot 0 starts right after the header+bitmap, which together occupy exactly one
        // WBFS sector; the wlba table itself starts right after that slot's disc-header copy.
        const uint64_t discInfoOffset = wbfsSectorSize_;
        const uint64_t wlbaTableOffset = discInfoOffset + 0x100;

        // The table is sized for the largest possible Wii disc regardless of the actual game, so
        // every real single-file .wbfs uses the same table length for a given sector size.
        constexpr uint64_t kMaxWiiDiscSize = 0x118240000ull;
        const uint64_t entryCount = (kMaxWiiDiscSize + wbfsSectorSize_ - 1) / wbfsSectorSize_;

        std::vector<uint8_t> raw(entryCount * 2);
        PreadExact(fd_, wlbaTableOffset, raw.data(), raw.size(), "the WBFS block table");
        wlbaTable_.resize(entryCount);
        for (uint64_t i = 0; i < entryCount; ++i) {
            wlbaTable_[i] = ReadBE16(&raw[i * 2]);
        }
    }

    void ReadAt(uint64_t offset, void* buffer, size_t size) override {
        auto* out = static_cast<uint8_t*>(buffer);
        size_t done = 0;
        while (done < size) {
            const uint64_t logical = offset + done;
            const uint64_t sectorIndex = logical / wbfsSectorSize_;
            const uint64_t withinSector = logical % wbfsSectorSize_;
            const size_t chunk =
                static_cast<size_t>(std::min<uint64_t>(size - done, wbfsSectorSize_ - withinSector));
            if (sectorIndex >= wlbaTable_.size()) {
                throw ExtractError("Read past the end of the WBFS disc table.");
            }
            const uint16_t physicalSector = wlbaTable_[sectorIndex];
            if (physicalSector == 0) {
                std::memset(out + done, 0, chunk);
            } else {
                PreadExact(fd_, uint64_t(physicalSector) * wbfsSectorSize_ + withinSector, out + done,
                           chunk, "the WBFS container");
            }
            done += chunk;
        }
    }

private:
    int fd_;
    uint64_t wbfsSectorSize_ = 0;
    std::vector<uint16_t> wlbaTable_;
};

// ----------------------------------------------------------------------------------------------
// Partition table, ticket/title-key decryption, and the decrypted-data random-access reader.
// ----------------------------------------------------------------------------------------------

struct PartitionInfo {
    uint64_t partitionOffset = 0;
    uint64_t dataOffset = 0;  // absolute
    uint64_t dataSize = 0;
    uint8_t titleKey[16] = {};
};

uint64_t FindDataPartitionOffset(ByteSource& src) {
    uint8_t tableHeader[32];
    src.ReadAt(0x40000, tableHeader, sizeof(tableHeader));
    for (int group = 0; group < 4; ++group) {
        const uint32_t count = ReadBE32(&tableHeader[group * 8]);
        const uint64_t tableOffset = uint64_t(ReadBE32(&tableHeader[group * 8 + 4])) << 2;
        if (count == 0) {
            continue;
        }
        std::vector<uint8_t> entries(uint64_t(count) * 8);
        src.ReadAt(tableOffset, entries.data(), entries.size());
        for (uint32_t i = 0; i < count; ++i) {
            const uint64_t partOffset = uint64_t(ReadBE32(&entries[i * 8])) << 2;
            const uint32_t partType = ReadBE32(&entries[i * 8 + 4]);
            if (partType == 0) {  // DATA partition
                return partOffset;
            }
        }
    }
    throw ExtractError("No data partition found - this doesn't look like a valid Wii game disc.");
}

PartitionInfo OpenDataPartition(ByteSource& src) {
    PartitionInfo info;
    info.partitionOffset = FindDataPartitionOffset(src);

    uint8_t ticket[0x2a4];
    src.ReadAt(info.partitionOffset, ticket, sizeof(ticket));

    if (ticket[0x1f1] != 0) {
        throw ExtractError(
            "This disc uses a non-standard encryption key (Korean/vWii release) - only "
            "standard retail PAL discs are supported.");
    }

    uint8_t iv[16] = {};
    std::memcpy(iv, &ticket[0x1dc], 8);  // top 8 bytes of the IV are the title ID, bottom 8 stay zero

    uint8_t titleKeyEncrypted[16];
    std::memcpy(titleKeyEncrypted, &ticket[0x1bf], 16);
    AesCbcDecrypt(kCommonKey, iv, titleKeyEncrypted, info.titleKey, 16);

    uint8_t partitionHeader[0x1c];
    src.ReadAt(info.partitionOffset + 0x2a4, partitionHeader, sizeof(partitionHeader));
    info.dataOffset = info.partitionOffset + (uint64_t(ReadBE32(&partitionHeader[0x14])) << 2);
    info.dataSize = uint64_t(ReadBE32(&partitionHeader[0x18])) << 2;
    return info;
}

// Random-access reader over one partition's DECRYPTED data stream. Decrypts whole 0x8000-byte
// clusters on demand; only the most recent cluster is cached, since extraction reads forward
// through each file but does jump between the FST and file bodies.
class DecryptedPartitionReader {
public:
    DecryptedPartitionReader(ByteSource& src, const PartitionInfo& info) : src_(src), info_(info) {}

    void ReadAt(uint64_t offset, void* buffer, size_t size) {
        auto* out = static_cast<uint8_t*>(buffer);
        size_t done = 0;
        while (done < size) {
            const uint64_t logical = offset + done;
            const uint64_t clusterIndex = logical / kClusterDataSize;
            const uint64_t withinCluster = logical % kClusterDataSize;
            const size_t chunk =
                static_cast<size_t>(std::min<uint64_t>(size - done, kClusterDataSize - withinCluster));
            EnsureClusterLoaded(clusterIndex);
            std::memcpy(out + done, &clusterPlain_[withinCluster], chunk);
            done += chunk;
        }
    }

private:
    void EnsureClusterLoaded(uint64_t clusterIndex) {
        if (loadedCluster_.has_value() && *loadedCluster_ == clusterIndex) {
            return;
        }
        uint8_t raw[kClusterSize];
        src_.ReadAt(info_.dataOffset + clusterIndex * kClusterSize, raw, kClusterSize);
        // The IV for the 0x7C00-byte data part is the cluster's own hash block bytes at 0x3D0,
        // taken directly from the still-encrypted cluster - this extractor never decrypts the
        // hash block itself since it only pulls file data out, it doesn't verify hash trees.
        uint8_t iv[16];
        std::memcpy(iv, raw + 0x3d0, 16);
        AesCbcDecrypt(info_.titleKey, iv, raw + kClusterHashSize, clusterPlain_.data(), kClusterDataSize);
        loadedCluster_ = clusterIndex;
    }

    ByteSource& src_;
    const PartitionInfo& info_;
    std::optional<uint64_t> loadedCluster_;
    std::array<uint8_t, kClusterDataSize> clusterPlain_{};
};

// ----------------------------------------------------------------------------------------------
// boot.bin / DOL / apploader / FST extraction into the destination DATA folder.
// ----------------------------------------------------------------------------------------------

void WriteFile(DecryptedPartitionReader& reader, uint64_t offset, uint64_t size, const fs::path& dest) {
    fs::create_directories(dest.parent_path());
    std::ofstream out(dest, std::ios::binary);
    if (!out) {
        throw ExtractError("Couldn't create " + dest.string());
    }
    std::vector<uint8_t> buffer(1 << 20);
    uint64_t remaining = size;
    uint64_t pos = offset;
    while (remaining > 0) {
        const size_t chunk = static_cast<size_t>(std::min<uint64_t>(remaining, buffer.size()));
        reader.ReadAt(pos, buffer.data(), chunk);
        out.write(reinterpret_cast<const char*>(buffer.data()), static_cast<std::streamsize>(chunk));
        pos += chunk;
        remaining -= chunk;
        g_bytesDone.fetch_add(chunk, std::memory_order_relaxed);
    }
}

// A DOL has 7 text + 11 data sections, each with its own offset (relative to the DOL's own
// start) and size in the 0x100-byte header; the file's real size is the furthest extent any
// section reaches (BSS isn't stored in the file, so it never extends the on-disc size).
uint32_t ComputeDolSize(DecryptedPartitionReader& reader, uint64_t dolOffset) {
    uint8_t header[0x100];
    reader.ReadAt(dolOffset, header, sizeof(header));
    uint32_t maxEnd = sizeof(header);
    for (int i = 0; i < 18; ++i) {
        const uint32_t sectionOffset = ReadBE32(&header[i * 4]);
        const uint32_t sectionSize = ReadBE32(&header[0x90 + i * 4]);
        if (sectionOffset != 0) {
            maxEnd = std::max(maxEnd, sectionOffset + sectionSize);
        }
    }
    return maxEnd;
}

uint32_t ComputeApploaderSize(DecryptedPartitionReader& reader, uint64_t apploaderOffset) {
    uint8_t header[0x20];
    reader.ReadAt(apploaderOffset, header, sizeof(header));
    const uint32_t size = ReadBE32(&header[0x14]);
    const uint32_t trailerSize = ReadBE32(&header[0x18]);
    return 0x20 + size + trailerSize;
}

// Standard GC/Wii FST: entries are 12 bytes each (type+name-offset packed into the first u32,
// then a file-offset/parent-index u32, then a file-size/subtree-end-index u32), followed by a
// string table. Wii file offsets are stored <<2, same convention as everywhere else in the disc.
void ExtractFst(DecryptedPartitionReader& reader, const fs::path& filesRoot) {
    uint8_t bootBin[0x440];
    reader.ReadAt(0, bootBin, sizeof(bootBin));
    const uint64_t fstOffset = uint64_t(ReadBE32(&bootBin[0x424])) << 2;
    const uint64_t fstSize = uint64_t(ReadBE32(&bootBin[0x428])) << 2;
    if (fstOffset == 0 || fstSize < 12 || fstSize > (256u << 20)) {
        throw ExtractError("The disc's file table looks invalid.");
    }

    std::vector<uint8_t> fst(fstSize);
    reader.ReadAt(fstOffset, fst.data(), fst.size());

    const uint32_t rootCount = ReadBE32(&fst[8]);  // root's "subtree end" IS the total entry count
    if (uint64_t(rootCount) * 12 > fst.size()) {
        throw ExtractError("The disc's file table looks invalid.");
    }
    const char* stringTable = reinterpret_cast<const char*>(fst.data()) + uint64_t(rootCount) * 12;
    const char* stringTableEnd = reinterpret_cast<const char*>(fst.data() + fst.size());

    auto entryName = [&](uint32_t index) -> std::string {
        const uint8_t* entry = &fst[uint64_t(index) * 12];
        const uint32_t nameOffset = ReadBE32(entry) & 0x00ffffff;
        const char* name = stringTable + nameOffset;
        if (name >= stringTableEnd) {
            return {};
        }
        const void* end = std::memchr(name, '\0', static_cast<size_t>(stringTableEnd - name));
        return std::string(name, end ? static_cast<const char*>(end) - name : 0);
    };

    std::function<void(uint32_t, uint32_t, const fs::path&)> walk = [&](uint32_t index, uint32_t end,
                                                                         const fs::path& dirPath) {
        while (index < end) {
            const uint8_t* entry = &fst[uint64_t(index) * 12];
            const bool isDir = entry[0] != 0;
            const uint32_t param1 = ReadBE32(entry + 4);
            const uint32_t param2 = ReadBE32(entry + 8);
            const std::string name = entryName(index);
            if (isDir) {
                walk(index + 1, param2, dirPath / name);
                index = param2;
            } else {
                WriteFile(reader, uint64_t(param1) << 2, param2, dirPath / name);
                ++index;
            }
        }
    };
    walk(1, rootCount, filesRoot);
}

}  // namespace

int WiiDiscExtractor_Extract(int sourceFd, const char* destDataFolder, char* errorOut,
                              int errorOutCapacity) {
    g_bytesDone.store(0, std::memory_order_relaxed);
    g_bytesTotal.store(0, std::memory_order_relaxed);

    auto setError = [&](const std::string& message) {
        if (errorOut != nullptr && errorOutCapacity > 0) {
            const size_t n = std::min(message.size(), static_cast<size_t>(errorOutCapacity - 1));
            std::memcpy(errorOut, message.data(), n);
            errorOut[n] = '\0';
        }
    };

    try {
        const fs::path dest(destDataFolder);
        const fs::path sysDir = dest / "sys";
        const fs::path filesDir = dest / "files";
        fs::create_directories(sysDir);
        fs::create_directories(filesDir);

        uint8_t magic[4];
        PreadExact(sourceFd, 0, magic, sizeof(magic), "the picked file");

        std::unique_ptr<ByteSource> source;
        if (std::memcmp(magic, "WBFS", 4) == 0) {
            source = std::make_unique<WbfsSource>(sourceFd);
        } else {
            source = std::make_unique<RawIsoSource>(sourceFd);
        }

        // The Kotlin picker only pre-checks plain ISO/GCM files (WBFS hides the game ID behind
        // its own block table), so this is the one check that always runs regardless of
        // container type.
        char gameId[6];
        source->ReadAt(0, gameId, sizeof(gameId));
        if (std::memcmp(gameId, "RMCP01", 6) != 0) {
            throw ExtractError("This disc's game ID is " + std::string(gameId, sizeof(gameId)) +
                                ", not RMCP01 (PAL Mario Kart Wii). Only the PAL release is supported.");
        }

        const PartitionInfo partition = OpenDataPartition(*source);
        g_bytesTotal.store(partition.dataSize, std::memory_order_relaxed);

        DecryptedPartitionReader reader(*source, partition);

        uint8_t bootBin[0x440];
        reader.ReadAt(0, bootBin, sizeof(bootBin));

        // dvd.cpp's WaitForAndroidDvdRoot (running concurrently on the native boot thread) treats
        // "sys/fst.bin and sys/main.dol exist" as "the DATA folder is ready" and starts booting
        // the moment it sees them - it has no way to know whether files/ is actually populated
        // yet. Those two are cheap to write and would normally land first, racing native boot
        // against the slow, multi-GB walk in ExtractFst() below: confirmed on-device, native
        // boot started and crashed trying to load game files that didn't exist on disk yet.
        // Extracting every real file FIRST, and only writing the small sys/ files (the readiness
        // signal) once that's fully done, makes the race impossible instead of just unlikely.
        ExtractFst(reader, filesDir);

        {
            std::ofstream out(sysDir / "boot.bin", std::ios::binary);
            out.write(reinterpret_cast<const char*>(bootBin), sizeof(bootBin));
        }
        {
            std::vector<uint8_t> bi2(0x2000);
            reader.ReadAt(0x400, bi2.data(), bi2.size());
            std::ofstream out(sysDir / "bi2.bin", std::ios::binary);
            out.write(reinterpret_cast<const char*>(bi2.data()), static_cast<std::streamsize>(bi2.size()));
        }

        const uint64_t dolOffset = uint64_t(ReadBE32(&bootBin[0x420])) << 2;
        WriteFile(reader, dolOffset, ComputeDolSize(reader, dolOffset), sysDir / "main.dol.tmp");

        constexpr uint64_t kApploaderOffset = 0x2440;
        WriteFile(reader, kApploaderOffset, ComputeApploaderSize(reader, kApploaderOffset),
                  sysDir / "apploader.img");

        const uint64_t fstOffset = uint64_t(ReadBE32(&bootBin[0x424])) << 2;
        const uint64_t fstSize = uint64_t(ReadBE32(&bootBin[0x428])) << 2;
        WriteFile(reader, fstOffset, fstSize, sysDir / "fst.bin.tmp");

        // Rename fst.bin and main.dol into place last, and in the same order dvd.cpp checks them
        // (fst.bin, then main.dol) - a rename is atomic on the same filesystem, so even the last
        // remaining sliver of a race (another thread reading this exact directory mid-write)
        // only ever sees "both missing" or "both present," never a half-written file.
        fs::rename(sysDir / "fst.bin.tmp", sysDir / "fst.bin");
        fs::rename(sysDir / "main.dol.tmp", sysDir / "main.dol");

        return 0;
    } catch (const std::exception& e) {
        setError(e.what());
        return -1;
    }
}

uint64_t WiiDiscExtractor_BytesDone() { return g_bytesDone.load(std::memory_order_relaxed); }
uint64_t WiiDiscExtractor_BytesTotal() { return g_bytesTotal.load(std::memory_order_relaxed); }

#endif  // __ANDROID__
