//
// RenderWare stream validation for custom model files.
//
// The downloader used to hand every downloaded DFF/TXD straight to the engine.
// A truncated file, an HTML error page from the CDN, or a chunk whose declared
// size is larger than the file itself ends up inside the RenderWare parser, and
// a malformed texture dictionary is what later leaves TextureDatabaseRuntime
// with entries it cannot resolve (the GetTexture / SortEntries crashes).
//
// The checks below are pure stream inspection, so they can run on the host with
// a byte array. They never guess engine offsets: every rule comes from the
// RenderWare binary chunk header, which is documented and always 12 bytes:
//
//   uint32 version   (0x0300 .. 0x0600 for the mobile/SA era)
//   uint32 type      (0x10 clump, 0x16 texture dictionary)
//   uint32 size      (bytes of payload that follow the header)
//
#pragma once

#include <cstdint>

enum class RwReject
{
    Ok = 0,
    TooSmall,
    BadVersion,
    WrongType,
    Truncated,
    TrailingGarbage,
    EmptyDictionary
};

struct RwHeader
{
    uint32_t version;
    uint32_t type;
    uint32_t size;
};

constexpr uint32_t kRwTypeClump = 0x10u;
constexpr uint32_t kRwTypeTexDictionary = 0x16u;
// The version field packs library version as 0xMMmmrrbb: RenderWare 3.6.0.3 is
// 0x36003 and the 3.x mobile assets in these data sets are all in that family.
// An earlier bound of 0x0300..0x0600 rejected every real file, which the host
// test caught, so the range is expressed the way the field is actually encoded.
constexpr uint32_t kRwVersionMin = 0x030000u;  // 3.0.0.0
constexpr uint32_t kRwVersionMax = 0x03FFFFu;  // 3.255.255.255
constexpr uint32_t kRwHeaderSize = 12u;
// A header claiming more than this cannot be a sane mobile asset and is treated
// as corruption rather than an allocation request.
constexpr uint32_t kRwMaxChunkBytes = 512u * 1024u * 1024u;

inline uint32_t ReadLe32(const unsigned char* data)
{
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
}

inline RwHeader ParseRwHeader(const unsigned char* data, int length, bool& readable)
{
    RwHeader header{0, 0, 0};
    readable = length >= (int)kRwHeaderSize;
    if (!readable) return header;
    header.version = ReadLe32(data);
    header.type = ReadLe32(data + 4);
    header.size = ReadLe32(data + 8);
    return header;
}

// Validates a header against the real size of the file it came from, so a
// caller that only read the first 12 bytes does not have to load the whole
// asset into memory. `fileSize` is the size on disk.
inline RwReject ValidateRenderWareHeader(const RwHeader& header, uint64_t fileSize,
                                        uint32_t expectedType, uint32_t& payloadSize)
{
    payloadSize = 0;

    if (fileSize < kRwHeaderSize) return RwReject::TooSmall;

    if (header.version < kRwVersionMin || header.version > kRwVersionMax)
        return RwReject::BadVersion;

    if (header.type != expectedType) return RwReject::WrongType;

    if (header.size == 0) return RwReject::EmptyDictionary;
    if (header.size > kRwMaxChunkBytes) return RwReject::Truncated;

    // The declared payload must actually be present. A downloaded file that is
    // shorter than its own header says was cut off mid transfer.
    const uint64_t needed = (uint64_t)kRwHeaderSize + header.size;
    if (needed > fileSize) return RwReject::Truncated;

    // RenderWare binary streams may carry a small amount of padding after the
    // top level chunk, but a large tail means the file is not what it claims.
    const uint64_t slack = fileSize - needed;
    if (slack > 64) return RwReject::TrailingGarbage;

    payloadSize = header.size;
    return RwReject::Ok;
}

// `expectedType` is kRwTypeClump for a DFF and kRwTypeTexDictionary for a TXD.
inline RwReject ValidateRenderWareStream(const unsigned char* data, int length,
                                         uint32_t expectedType, uint32_t& payloadSize)
{
    payloadSize = 0;
    if (length < (int)kRwHeaderSize) return RwReject::TooSmall;

    bool readable = false;
    const RwHeader header = ParseRwHeader(data, length, readable);
    if (!readable) return RwReject::TooSmall;

    return ValidateRenderWareHeader(header, (uint64_t)length, expectedType, payloadSize);
}

inline const char* DescribeRwReject(RwReject reject)
{
    switch (reject)
    {
        case RwReject::Ok:               return "ok";
        case RwReject::TooSmall:         return "berkas terlalu kecil untuk header RenderWare";
        case RwReject::BadVersion:       return "versi RenderWare tidak dikenal";
        case RwReject::WrongType:        return "tipe chunk salah (bukan DFF/TXD yang diminta)";
        case RwReject::Truncated:        return "berkas terpotong: chunk lebih besar dari isi berkas";
        case RwReject::TrailingGarbage:  return "ada data berlebih setelah chunk";
        case RwReject::EmptyDictionary:  return "chunk kosong";
    }
    return "unknown";
}
