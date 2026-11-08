// Host tests for the guards added for the Texture Database and Custom Models
// crashes. Every header under test is the real header from the source tree; only
// the engine (libGTASA) is absent, and none of these files touch it.
//
// Covered:
//   * GetTextureGuard::ClassifyBytes + SpanCovers  - the null / garbage name
//     argument from the GetTexture crash report
//   * RenderWareStreamGuard::ValidateRenderWareHeader - truncated, wrong-type and
//     oversized custom DFF/TXD files
//   * TextureVariant::Choose - the "player" database that was both "failed to
//     load" and "loaded"
//   * GameStartupStage::ShouldAcceptStage - stage ordering for the loading screen
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include "GetTextureGuard.h"
#include "RenderWareStreamGuard.h"
#include "TextureVariantDecision.h"
#include "GameStartupStage.h"

static int g_failures = 0;

static void Check(bool condition, const char* what)
{
    printf("%s  %s\n", condition ? "PASS" : "FAIL", what);
    if (!condition) g_failures++;
}

// ---------------------------------------------------------------------------
// GetTextureGuard
// ---------------------------------------------------------------------------
static void TestTextureNameClassification()
{
    int length = -1;

    using GetTextureGuard::ClassifyBytes;
    using GetTextureGuard::Outcome;

    Check(ClassifyBytes(nullptr, 64, length) == Outcome::RejectNull,
          "GetTexture(null) is rejected before any read");
    Check(length == 0, "rejected name reports length 0");

    const unsigned char empty[] = {0};
    Check(ClassifyBytes(empty, 1, length) == Outcome::RejectNull,
          "GetTexture(\"\") is rejected");

    const unsigned char good[] = "carlito\0";
    Check(ClassifyBytes(good, sizeof(good), length) == Outcome::Pass,
          "a normal texture name passes");
    Check(length == 7, "length is the string length, not the buffer length");

    // The crash reported x2 = 0x6a006f73: bytes that cannot be an ASCII name.
    const unsigned char utf16[] = {0x73, 0x00, 0x6f, 0x00, 0x6a, 0x00, 0x00, 0x00};
    Check(ClassifyBytes(utf16, sizeof(utf16), length) == Outcome::RejectEncoding,
          "UTF-16 bytes (x2 = 0x6a006f73) are rejected as a non-ASCII name");

    const unsigned char highBytes[] = {0xA3, 0x91, 0xFF, 0x00};
    Check(ClassifyBytes(highBytes, sizeof(highBytes), length) == Outcome::RejectEncoding,
          "non-printable bytes are rejected");

    const unsigned char zeroAvailable[] = "abc";
    Check(ClassifyBytes(zeroAvailable, 0, length) == Outcome::RejectNull,
          "a zero sized window is rejected even for a readable pointer");

    // No terminator inside the window: refuse rather than walk further.
    std::vector<unsigned char> unterminated(GetTextureGuard::kSafeCopyLength, 'a');
    Check(ClassifyBytes(unterminated.data(), (int)unterminated.size(), length)
              == Outcome::RejectEncoding,
          "a name longer than the copy limit is refused instead of over-reading");

    // Exactly at the limit with a terminator is still a name.
    std::vector<unsigned char> atLimit(GetTextureGuard::kSafeCopyLength, 'b');
    atLimit.back() = 0;
    Check(ClassifyBytes(atLimit.data(), (int)atLimit.size(), length) == Outcome::Pass,
          "a name that ends exactly at the copy limit passes");
}

static void TestSpanCovers()
{
    using GetTextureGuard::SpanCovers;
    const uintptr_t base = 0x7f00000000ull;

    Check(SpanCovers(base, base + 0x1000, base + 0x100, 16), "span inside the mapping is accepted");
    Check(SpanCovers(base, base + 0x1000, base, 0x1000), "span covering the whole mapping is accepted");
    Check(!SpanCovers(base, base + 0x1000, base + 0xFFF, 2), "span crossing the end is refused");
    Check(!SpanCovers(base, base + 0x1000, base - 1, 4), "span starting before the mapping is refused");
    Check(!SpanCovers(base, base + 0x1000, base, 0), "zero length is refused");
    Check(!SpanCovers(base, base, base, 1), "empty mapping is refused");

    // The GetTexture fault address relative to a huge base must not wrap around.
    const uintptr_t high = UINTPTR_MAX - 8;
    Check(!SpanCovers(high, high + 4, high, 16),
          "a pointer near the top of the address space cannot wrap into acceptance");
}

// ---------------------------------------------------------------------------
// RenderWare custom model validation
// ---------------------------------------------------------------------------
static void PutLe32(std::vector<unsigned char>& data, size_t at, uint32_t value)
{
    data[at + 0] = (unsigned char)(value & 0xFF);
    data[at + 1] = (unsigned char)((value >> 8) & 0xFF);
    data[at + 2] = (unsigned char)((value >> 16) & 0xFF);
    data[at + 3] = (unsigned char)((value >> 24) & 0xFF);
}

static std::vector<unsigned char> MakeRwFile(uint32_t version, uint32_t type, uint32_t size,
                                             size_t actualPayload)
{
    std::vector<unsigned char> data(12 + actualPayload, 0);
    PutLe32(data, 0, version);
    PutLe32(data, 4, type);
    PutLe32(data, 8, size);
    return data;
}

static void TestRenderWareValidation()
{
    uint32_t payload = 0;

    // A well formed DFF: clump chunk, 4 bytes of payload actually present.
    std::vector<unsigned char> dff = MakeRwFile(0x34000u, kRwTypeClump, 4, 4);
    bool readable = false;
    const RwHeader parsed = ParseRwHeader(dff.data(), (int)dff.size(), readable);
    Check(readable, "a 12 byte header is parseable");
    Check(ValidateRenderWareHeader(parsed, dff.size(), kRwTypeClump, payload) == RwReject::Ok,
          "a complete DFF header is accepted");
    Check(payload == 4, "payload size is reported");

    Check(ValidateRenderWareStream(nullptr, 0, kRwTypeClump, payload) == RwReject::TooSmall,
          "an empty custom model file is refused");
    Check(ValidateRenderWareStream(dff.data(), 8, kRwTypeClump, payload) == RwReject::TooSmall,
          "a file shorter than the header is refused");

    // Truncated: header claims 4096 bytes, file holds 16.
    std::vector<unsigned char> truncated = MakeRwFile(0x34000u, kRwTypeTexDictionary, 4096, 4);
    Check(ValidateRenderWareStream(truncated.data(), (int)truncated.size(),
                                   kRwTypeTexDictionary, payload) == RwReject::Truncated,
          "a truncated TXD download is refused");

    // Wrong chunk type: a DFF offered where a TXD is expected.
    std::vector<unsigned char> wrongType = MakeRwFile(0x34000u, kRwTypeClump, 4, 4);
    Check(ValidateRenderWareStream(wrongType.data(), (int)wrongType.size(),
                                   kRwTypeTexDictionary, payload) == RwReject::WrongType,
          "a DFF is refused when a TXD is required");

    // Version outside the known RenderWare range.
    std::vector<unsigned char> badVersion = MakeRwFile(0x9999u, kRwTypeClump, 4, 4);
    Check(ValidateRenderWareStream(badVersion.data(), (int)badVersion.size(),
                                   kRwTypeClump, payload) == RwReject::BadVersion,
          "an unknown RenderWare version is refused");

    // Oversized chunk: a header that would otherwise ask for a huge allocation.
    std::vector<unsigned char> huge = MakeRwFile(0x34000u, kRwTypeClump, 0xFFFFFFFFu, 4);
    Check(ValidateRenderWareStream(huge.data(), (int)huge.size(),
                                   kRwTypeClump, payload) == RwReject::Truncated,
          "an absurd chunk size is refused instead of trusted");

    std::vector<unsigned char> emptyChunk = MakeRwFile(0x34000u, kRwTypeClump, 0, 0);
    Check(ValidateRenderWareStream(emptyChunk.data(), (int)emptyChunk.size(),
                                   kRwTypeClump, payload) == RwReject::EmptyDictionary,
          "an empty chunk is refused");

    // Trailing garbage: file much longer than its own header says.
    std::vector<unsigned char> trailing = MakeRwFile(0x34000u, kRwTypeClump, 4, 4096);
    Check(ValidateRenderWareStream(trailing.data(), (int)trailing.size(),
                                   kRwTypeClump, payload) == RwReject::TrailingGarbage,
          "a file with a large tail after the chunk is refused");

    // A little padding is tolerated: RenderWare writers align chunks.
    std::vector<unsigned char> padded = MakeRwFile(0x34000u, kRwTypeClump, 4, 40);
    Check(ValidateRenderWareStream(padded.data(), (int)padded.size(),
                                   kRwTypeClump, payload) == RwReject::Ok,
          "small alignment padding is accepted");

    Check(std::strlen(DescribeRwReject(RwReject::Truncated)) > 0,
          "every rejection has a message for the log");
}

// ---------------------------------------------------------------------------
// Texture variant decision (the "player" database in the crash report)
// ---------------------------------------------------------------------------
static void Fill(TextureVariant::FilesPresent& files, TextureVariant::Variant variant,
                 bool tmb, bool dat, bool toc)
{
    if (tmb) files.Add(variant, TextureVariant::Part::Thumbs);
    if (dat) files.Add(variant, TextureVariant::Part::Data);
    if (toc) files.Add(variant, TextureVariant::Part::Table);
}

static void TestVariantDecision()
{
    using namespace TextureVariant;

    // The reported data set: dxt and pvr complete, etc missing its .tmb.
    FilesPresent reported;
    Fill(reported, Dxt, true, true, true);
    Fill(reported, Pvr, true, true, true);
    Fill(reported, Etc, false, true, true);

    Plan plan = Choose(Dxt, reported, false);
    Check(plan.decision == Decision::UseRequested && plan.variant == Dxt,
          "a complete requested variant is used");
    Check(ShouldPublishLoaded(plan.decision), "that database may be published as loaded");

    plan = Choose(Etc, reported, false);
    Check(plan.decision == Decision::WantsFallback,
          "etc with a missing tmb is not treated as complete");
    Check(!ShouldPublishLoaded(plan.decision),
          "a database that could not load is never published as loaded");
    Check(plan.completeVariants == 2, "the two usable variants are counted");

    // A variant with a tmb but no data/table is also incomplete.
    FilesPresent partial;
    Fill(partial, Pvr, true, false, false);
    plan = Choose(Pvr, partial, false);
    Check(plan.decision == Decision::NoVariant,
          "tmb without dat/toc is not a loadable variant");
    Check(!ShouldPublishLoaded(plan.decision), "partial variant is not published as loaded");

    // With switching enabled a complete variant may be substituted.
    plan = Choose(Etc, reported, true);
    Check(plan.decision == Decision::UseFallback, "a complete variant is used when switching is enabled");
    Check(plan.variant != Etc, "the substitute is not the incomplete variant");
    Check(ShouldPublishLoaded(plan.decision), "the substituted database is loadable");

    // Nothing on disk at all: never publish.
    FilesPresent nothing;
    plan = Choose(Dxt, nothing, true);
    Check(plan.decision == Decision::NoVariant && !ShouldPublishLoaded(plan.decision),
          "an empty data set leaves the database unloaded");

    // Requesting a format the table does not model must be handled without a crash.
    FilesPresent onlyUnc;
    Fill(onlyUnc, Uncompressed, true, true, true);
    plan = Choose(Uncompressed, onlyUnc, false);
    Check(plan.decision == Decision::UseRequested, "the uncompressed variant is selectable");

    // A database with only uncompressed available must not be forced into dxt.
    FilesPresent dxtMissing;
    Fill(dxtMissing, Uncompressed, true, true, true);
    plan = Choose(Dxt, dxtMissing, false);
    Check(plan.decision == Decision::WantsFallback && plan.variant == Dxt,
          "without switching the requested format is kept, never the wrong one");

    Check(std::strcmp(SuffixOf(Dxt), "dxt") == 0, "dxt suffix matches the file naming");
    Check(std::strcmp(SuffixOf(Etc), "etc") == 0, "etc suffix matches the file naming");
}

// ---------------------------------------------------------------------------
// Startup stage ordering (loading screen honesty)
// ---------------------------------------------------------------------------
static void TestStartupStageOrdering()
{
    Check(ShouldAcceptStage(StageUnknown, StageDataCheck), "the first stage is accepted");
    Check(ShouldAcceptStage(StageDataCheck, StageRenderWare), "stages move forward");
    Check(!ShouldAcceptStage(StageRenderWare, StageGameInit),
          "a repeated initialise cannot move the loading screen backwards");
    Check(!ShouldAcceptStage(StageTextureDb, StageTextureDb),
          "repeating the same stage is ignored");
    Check(!ShouldAcceptStage(StageUnknown, StageUnknown), "the unknown stage is never published");
    Check(!ShouldAcceptStage(StageReady, StageReady + 1), "a stage past the end is refused");
}

int main()
{
    printf("--- GetTexture name guard ---\n");
    TestTextureNameClassification();
    TestSpanCovers();

    printf("\n--- RenderWare custom model validation ---\n");
    TestRenderWareValidation();

    printf("\n--- Texture variant decision ---\n");
    TestVariantDecision();

    printf("\n--- Startup stage ordering ---\n");
    TestStartupStageOrdering();

    printf("\n%s (%d failure(s))\n", g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures);
    return g_failures == 0 ? 0 : 1;
}
