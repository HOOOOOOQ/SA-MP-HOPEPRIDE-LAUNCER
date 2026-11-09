//
// Texture variant choice from the GPU's advertised capabilities.
//
// Evidence (crashv3.log, PID 15849, 19:37:55.859 - 19:38:03.831):
//
//   Inventory, written by the installed data-probe:
//     texdb 'player' variant dxt: tmb=ada(6940)   dat=ada(6569414)  toc=ada(1580)
//     texdb 'player' variant pvr: tmb=ada(6940)   dat=ada(6743446)  toc=ada(1580)
//   The engine asks for a different variant:
//     Fail open file .../texdb/player/player.etc.tmb        (file does not exist)
//     texdb 'player': variant 5 (etc) failed to load
//     SortEntries: 'player' failed to load earlier, sort skipped
//     texdb 'player': loaded                                 (contradiction)
//   Eleven seconds later, still inside that state:
//     SIGSEGV, fault addr 0x116d91a38, x0 = 0x45b6468e
//     TextureDatabaseRuntime::GetTexture(char const*)+120
//   and 0x45b6468e * 4 == 0x116d91a38 exactly: the engine indexed a texture
//   entry array with a name hash and landed on an unmapped page, because the
//   player database has no entries to index.
//
// So the guard on GetTexture was never reached for this crash: its own log shows
// only empty names being rejected, and the faulting call carries a real name. The
// database it needed had been failed by the engine before any texture was asked
// for. That failure is a *choice*: the GPU did not ask for "etc", the engine
// assumed it, and this data set ships dxt and pvr only.
//
// This header decides which variant to load from what the GL driver advertises,
// so the engine never asks for a variant that cannot exist on this device:
//
//   * a device that advertises no compressed-texture extension at all gets the
//     uncompressed set, which is what its driver can actually upload;
//   * ETC1 / ETC2 decoders must not be credited with DXT or PVR support: those
//     are separate extensions (GL_EXT_texture_compression_s3tc, IMG_texture_
//     compression_pvrtc);
//   * an unknown extension string yields "no constraint", never a guess, so a
//     driver that reports nothing keeps the engine's own choice.
//
// Free of engine and GL headers so the rules run on the host (see
// tools/hosttest/texture_gpu_test.cpp).
//
#pragma once

namespace TextureGpu
{

// Threshold flags, taken from the GL enums rather than from strings so the
// caller does not have to translate twice.
constexpr unsigned kDxt = 1u << 0;   // GL_EXT_texture_compression_s3tc / dxt1
constexpr unsigned kPvrtc = 1u << 1; // IMG_texture_compression_pvrtc
constexpr unsigned kEtc1 = 1u << 2;  // OES_compressed_ETC1_RGB8_texture

// Engine format ids, repeated here so this header stays dependency free. The
// values are pinned by TextureDatabase.h (DF_UNC = 0, DF_DXT = 1, DF_PVR = 4,
// DF_ETC = 5, DF_Default = 6) and checked in the host test.
enum Format
{
    FormatUncompressed = 0,
    FormatDxt = 1,
    FormatPvr = 4,
    FormatEtc = 5,
    FormatDefault = 6
};

// Case-insensitive containment, over bytes. Written out so this header does not
// need <string> or <cstring> and stays trivially testable.
inline bool ContainsFolded(const char* haystack, const char* needle)
{
    if (!haystack || !needle) return false;
    if (!needle[0]) return true;

    for (int start = 0; haystack[start]; start++)
    {
        int index = 0;
        while (true)
        {
            const char expected = needle[index];
            if (!expected) return true;

            char actual = haystack[start + index];
            if (!actual) return false;

            // ASCII folding only: extension strings are ASCII by contract, and a
            // locale-dependent tolower() has no place in a hot path.
            if (actual >= 'A' && actual <= 'Z') actual = (char)(actual - 'A' + 'a');
            char wanted = expected;
            if (wanted >= 'A' && wanted <= 'Z') wanted = (char)(wanted - 'A' + 'a');

            if (actual != wanted) break;
            index++;
        }
    }
    return false;
}

// Extension list -> capability bits. Absent substrings simply do not set a bit.
inline unsigned CapabilitiesFromExtensions(const char* extensions)
{
    unsigned caps = 0;
    if (!extensions) return caps;

    // The two spellings Mali, Adreno and PowerVR actually publish for S3TC/DXT.
    if (ContainsFolded(extensions, "GL_EXT_texture_compression_s3tc") ||
        ContainsFolded(extensions, "GL_EXT_texture_compression_dxt1"))
        caps |= kDxt;

    if (ContainsFolded(extensions, "IMG_texture_compression_pvrtc") ||
        ContainsFolded(extensions, "GL_IMG_texture_compression_pvrtc"))
        caps |= kPvrtc;

    if (ContainsFolded(extensions, "OES_compressed_ETC1_RGB8_texture") ||
        ContainsFolded(extensions, "GL_OES_compressed_ETC1_RGB8_texture"))
        caps |= kEtc1;

    return caps;
}

// What the engine should be told to load, given the GPU's capabilities.
//
// Returns FormatDefault when no constraint can be derived: the engine keeps its
// own choice, which is the behaviour of every build before this one.
inline int PreferredVariant(unsigned capabilities)
{
    // No compressed format at all: uncompressed is the only thing that can be
    // uploaded, so do not let the engine ask for a packed variant.
    if ((capabilities & (kDxt | kPvrtc | kEtc1)) == 0) return FormatUncompressed;

    // Prefer the family the engine is most likely to name first on this class of
    // device. DXT is the one GTA:SA's mobile data sets ship most often, and the
    // inventory in the crash log proves dxt and pvr exist side by side.
    if (capabilities & kDxt) return FormatDxt;
    if (capabilities & kPvrtc) return FormatPvr;
    if (capabilities & kEtc1) return FormatEtc;

    return FormatDefault;
}

// True when a variant the engine asks for can be decoded on this device. Used to
// veto a request instead of trusting it, because a failed load is what leaves the
// database unusable.
inline bool IsDecodable(int format, unsigned capabilities)
{
    switch (format)
    {
        case FormatUncompressed: return true;                 // always decodable
        case FormatDxt:          return (capabilities & kDxt) != 0;
        case FormatPvr:          return (capabilities & kPvrtc) != 0;
        case FormatEtc:          return (capabilities & kEtc1) != 0;
        default:                 return true;                 // not modelled: leave it alone
    }
}

// The decision the loader uses, in one place so the host test exercises the real
// rule rather than a copy of it.
//
//   requested  what the engine passed in
//   preferred  PreferredVariant(capabilities)
//   capabilities from the GL extension string
//
// A request that this GPU cannot decode is replaced by the preferred variant;
// anything else is passed through untouched.
inline int ResolveRequestedVariant(int requested, unsigned capabilities)
{
    if (requested != FormatDxt && requested != FormatPvr && requested != FormatEtc)
        return requested;         // uncompressed, default, 360/ps3: not ours to change

    if (IsDecodable(requested, capabilities)) return requested;

    const int preferred = PreferredVariant(capabilities);

    // Only substitute a concrete, decodable format. "Default" here would hand the
    // engine the same unpickable choice it just failed on.
    if (preferred == FormatUncompressed || preferred == FormatDxt ||
        preferred == FormatPvr || preferred == FormatEtc)
        return preferred;

    return requested;
}

inline const char* SuffixOf(int format)
{
    switch (format)
    {
        case FormatUncompressed: return "unc";
        case FormatDxt:          return "dxt";
        case FormatPvr:          return "pvr";
        case FormatEtc:          return "etc";
        default:                 return nullptr;
    }
}

} // namespace TextureGpu
