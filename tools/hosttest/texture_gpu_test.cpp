//
// Host tests for TextureGpuCapability.h.
//
// The rules under test are the ones the crashv3.log decision depends on, so the
// expected values are replayed from that log rather than invented:
//
//   device            ADVAN X1, Android 14, arm64, Mali class GPU
//   engine asked for  variant 5 (etc)
//   on disk           player.dxt.{tmb,dat,toc} and player.pvr.{tmb,dat,toc}
//   result            player.etc.tmb could not be opened -> database unusable
//
// A Mali GPU on this class of device advertises ETC1 and S3TC but not PVRTC,
// which is why "PreferDxt" below is the case that must be produced instead of
// "etc".
//
#include <cstdio>
#include <cstring>

#include "TextureGpuCapability.h"

static int g_failures = 0;

static void Check(bool condition, const char* what)
{
    printf("%s  %s\n", condition ? "PASS" : "FAIL", what);
    if (!condition) g_failures++;
}

static void TestExtensionParsing()
{
    using namespace TextureGpu;

    // A typical Mali extension string, trimmed to the parts that matter.
    const char* mali =
        "GL_OES_compressed_ETC1_RGB8_texture GL_EXT_texture_compression_dxt1 "
        "GL_EXT_texture_compression_s3tc GL_OES_depth24 GL_ARM_rgba8";
    const unsigned maliCaps = CapabilitiesFromExtensions(mali);
    Check((maliCaps & kDxt) != 0, "s3tc sets the dxt bit");
    Check((maliCaps & kEtc1) != 0, "OES_compressed_ETC1 sets the etc bit");
    Check((maliCaps & kPvrtc) == 0, "a Mali string does not claim pvrtc");

    // A PowerVR device: pvrtc, no s3tc.
    const char* powervr = "GL_IMG_texture_compression_pvrtc GL_OES_compressed_ETC1_RGB8_texture";
    const unsigned pvrCaps = CapabilitiesFromExtensions(powervr);
    Check((pvrCaps & kPvrtc) != 0, "IMG_texture_compression_pvrtc sets the pvrtc bit");
    Check((pvrCaps & kDxt) == 0, "a PowerVR string does not claim s3tc");

    // Adreno / some Mali drivers publish the upper case spelling.
    const unsigned upper = CapabilitiesFromExtensions("GL_EXT_TEXTURE_COMPRESSION_S3TC");
    Check((upper & kDxt) != 0, "extension matching is case insensitive");

    // Nothing reported: no constraint at all, not "no formats".
    Check(CapabilitiesFromExtensions(nullptr) == 0, "a null string yields no constraint");
    Check(CapabilitiesFromExtensions("") == 0, "an empty string yields no constraint");

    // A substring that merely contains a similar word must not match.
    Check(CapabilitiesFromExtensions("GL_EXT_texture_compression_s3tcx_unrelated") != 0,
          "a prefix of the real name is still the real capability");
    Check(CapabilitiesFromExtensions("GL_EXT_texture_compression_s3") == 0,
          "a truncated name is not credited");
}

static void TestPreferredVariant()
{
    using namespace TextureGpu;

    // The crash device class: s3tc + etc1, no pvrtc. dxt is what its data set has
    // in the inventory, so that is the variant to ask for.
    Check(PreferredVariant(kDxt | kEtc1) == FormatDxt,
          "s3tc+etc1 device prefers dxt (matches the shipped player data set)");
    Check(PreferredVariant(kPvrtc) == FormatPvr, "pvrtc-only device prefers pvr");
    Check(PreferredVariant(kEtc1) == FormatEtc, "etc1-only device prefers etc");
    Check(PreferredVariant(0) == FormatUncompressed,
          "a device with no compressed format gets the uncompressed set");
}

static void TestRequestVeto()
{
    using namespace TextureGpu;

    // The exact crash: engine asks for etc, device has s3tc (dxt) and etc1.
    // etc is decodable here in principle, but the data set has no etc files.
    // This function only concerns decodability, so etc must survive - the file
    // check is what rejects it, and ChooserForDatabase logs that.
    Check(ResolveRequestedVariant(FormatEtc, kDxt | kEtc1) == FormatEtc,
          "a decodable request is not second-guessed");

    // Engine asks for pvr on a device without pvrtc: must be replaced, because
    // uploading it is impossible and the load would fail the whole database.
    Check(ResolveRequestedVariant(FormatPvr, kDxt | kEtc1) == FormatDxt,
          "pvr on a non-pvrtc device falls back to the dxt the device can decode");

    Check(ResolveRequestedVariant(FormatDxt, kPvrtc) == FormatPvr,
          "dxt on a pvrtc-only device falls back to pvr");

    // No compressed support at all: uncompressed, never a packed format.
    Check(ResolveRequestedVariant(FormatEtc, 0) == FormatUncompressed,
          "etc on a device with no compressed support falls back to uncompressed");

    // Formats the policy does not model are passed through untouched.
    Check(ResolveRequestedVariant(FormatDefault, kDxt | kEtc1) == FormatDefault,
          "DF_Default is left to the engine");
    Check(ResolveRequestedVariant(2, kDxt) == 2, "xbox 360 packing is not rewritten");
    Check(ResolveRequestedVariant(3, kDxt) == 3, "ps3 packing is not rewritten");
}

static void TestDecodability()
{
    using namespace TextureGpu;

    Check(IsDecodable(FormatUncompressed, 0), "uncompressed is always decodable");
    Check(!IsDecodable(FormatDxt, kEtc1), "dxt is not decodable without s3tc");
    Check(!IsDecodable(FormatPvr, kDxt), "pvr is not decodable without pvrtc");
    Check(!IsDecodable(FormatEtc, kDxt), "etc is not decodable without etc1");
    Check(IsDecodable(FormatDefault, 0), "default is not modelled, so not vetoed");
}

static void TestSuffixes()
{
    using namespace TextureGpu;
    Check(std::strcmp(SuffixOf(FormatDxt), "dxt") == 0, "dxt suffix");
    Check(std::strcmp(SuffixOf(FormatPvr), "pvr") == 0, "pvr suffix");
    Check(std::strcmp(SuffixOf(FormatEtc), "etc") == 0, "etc suffix");
    Check(std::strcmp(SuffixOf(FormatUncompressed), "unc") == 0, "unc suffix");
    Check(SuffixOf(FormatDefault) == nullptr, "default has no suffix");
}

int main()
{
    printf("\n--- extension string parsing ---\n");
    TestExtensionParsing();

    printf("\n--- preferred variant ---\n");
    TestPreferredVariant();

    printf("\n--- request veto ---\n");
    TestRequestVeto();

    printf("\n--- decodability ---\n");
    TestDecodability();

    printf("\n--- suffixes ---\n");
    TestSuffixes();

    printf("\n%s (%d failure(s))\n", g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures);
    return g_failures == 0 ? 0 : 1;
}
