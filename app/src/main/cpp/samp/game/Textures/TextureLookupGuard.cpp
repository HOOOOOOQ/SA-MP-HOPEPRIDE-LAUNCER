//
// TextureDatabaseRuntime::GetTexture protection.
//
// See GetTextureGuard.h for the crash evidence this addresses. This file adds
// the pieces that need process memory instead of plain values:
//
//   * MappedReadable() - asks the kernel whether a byte range is readable before
//                        the guard reads it: no fixed offsets, no guessing, and
//                        a failure to answer counts as "not readable".
//   * The hook itself  - validates the name, then calls the engine with a local
//                        copy so a caller that frees its buffer while the engine
//                        compares cannot turn into a fault.
//
// Installed from TextureVariantFallback::Initialise(), which already runs after
// libGTASA is mapped and before InitialiseRenderWare.
//

#include "TextureLookupGuard.h"
#include "GetTextureGuard.h"
#include "../EngineTelemetry.h"
#include "../SafeMode.h"
#include "../../main.h"

#include <cstdio>
#include <cstring>
#include <cstdint>

namespace
{

using namespace GetTextureGuard;

// /proc/self/maps cache. The engine looks textures up in bursts; re-reading the
// map file for every lookup would cost more than the lookup. The snapshot is
// refreshed whenever a range is not found, so a database that unmapped since the
// last read cannot produce a stale "readable" answer.
constexpr int kMaxCachedRegions = 256;

struct ReadableRegion
{
    uintptr_t start;
    uintptr_t end;
};

ReadableRegion g_regions[kMaxCachedRegions];
int g_regionCount = -1;   // -1 = not loaded yet

void LoadReadableRegions()
{
    g_regionCount = 0;

    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) return;

    char line[512];
    while (g_regionCount < kMaxCachedRegions && fgets(line, sizeof(line), maps))
    {
        // "7f0a100000-7f0a1ff000 r-xp 00000000 fd:01 1234 /system/lib64/..."
        unsigned long long start = 0, end = 0;
        char permissions[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &start, &end, permissions) != 3) continue;
        if (permissions[0] != 'r') continue;   // a write-only mapping is unreadable
        if (end <= start) continue;

        // Merge directly adjacent readable regions so the cache stays small.
        if (g_regionCount > 0 && g_regions[g_regionCount - 1].end == (uintptr_t)start)
        {
            g_regions[g_regionCount - 1].end = (uintptr_t)end;
            continue;
        }

        g_regions[g_regionCount].start = (uintptr_t)start;
        g_regions[g_regionCount].end = (uintptr_t)end;
        g_regionCount++;
    }

    fclose(maps);
}

bool SpanInSnapshot(uintptr_t start, uintptr_t length)
{
    for (int index = 0; index < g_regionCount; index++)
    {
        if (SpanCovers(g_regions[index].start, g_regions[index].end, start, length))
            return true;
    }
    return false;
}

// True only when every byte in [pointer, pointer + length) is inside a readable
// mapping. Fails closed.
bool MappedReadable(const void* pointer, size_t length)
{
    const uintptr_t start = reinterpret_cast<uintptr_t>(pointer);
    if (start == 0 || length == 0) return false;

    if (g_regionCount < 0) LoadReadableRegions();
    if (g_regionCount <= 0) return false;

    if (SpanInSnapshot(start, (uintptr_t)length)) return true;

    // Mappings change while the game runs, so re-read the map before rejecting.
    LoadReadableRegions();
    return SpanInSnapshot(start, (uintptr_t)length);
}

// Rejection counters per reason, printed by the crash handler so a report says
// which validation rule fired and how often.
volatile unsigned long long g_rejectedNull = 0;
volatile unsigned long long g_rejectedRange = 0;
volatile unsigned long long g_rejectedEncoding = 0;
volatile unsigned long long g_loggedRejections = 0;
volatile unsigned long long g_nullReturned = 0;

typedef void* (*GetTextureFn)(const char* name);
GetTextureFn orig_GetTexture = nullptr;

void LogRejectionCopy(Outcome outcome, const unsigned char* data, int length)
{
    // Bounded logging: the first few rejections are informative, thousands of
    // them are not, and this path may run at frame rate. Only bytes already
    // copied out of process memory are printed, never the caller's pointer.
    const unsigned long long logged = ++g_loggedRejections;
    if (logged > 12) return;

    char printable[GetTextureGuard::kSafeCopyLength];
    int index = 0;
    if (data)
    {
        for (; index < length && index < (int)sizeof(printable) - 1; index++)
        {
            const unsigned char value = data[index];
            if (value == 0) break;
            printable[index] = (value >= 0x20 && value <= 0x7E) ? (char)value : '?';
        }
    }
    printable[index] = 0;

    FLog("GetTexture ditolak: %s (isi=\"%s\")", Describe(outcome), printable);
}

void* HandleCall(const char* name)
{
    const uintptr_t raw = reinterpret_cast<uintptr_t>(name);
    if (raw == 0)
    {
        g_rejectedNull++;
        EngineTelemetry::CountTextureNameRejected();
        LogRejectionCopy(Outcome::RejectNull, nullptr, 0);
        g_nullReturned++;
        return nullptr;
    }

    if (raw < kMinimumNamePointer)
    {
        g_rejectedRange++;
        EngineTelemetry::CountTextureNameRejected();
        LogRejectionCopy(Outcome::RejectRange, nullptr, 0);
        g_nullReturned++;
        return nullptr;
    }

    // Read the name into a local buffer, but only bytes the kernel says are
    // mapped. One name never needs more than kSafeCopyLength bytes.
    unsigned char copy[kSafeCopyLength];
    size_t copied = 0;

    if (MappedReadable(name, kSafeCopyLength))
    {
        memcpy(copy, name, kSafeCopyLength);
        copied = kSafeCopyLength;
    }
    else
    {
        // A legitimate short name can sit at the very end of a mapping, so fall
        // back to reading the bytes that are mapped, one at a time.
        while (copied < kSafeCopyLength && MappedReadable(name + copied, 1))
        {
            copy[copied] = ((const unsigned char*)name)[copied];
            if (copy[copied] == 0) break;
            copied++;
        }
        if (copied < kSafeCopyLength) copy[copied] = 0;
        else copied = kSafeCopyLength;
    }

    int length = 0;
    const Outcome outcome = ClassifyBytes(copy, (int)copied, length);
    if (outcome != Outcome::Pass)
    {
        if (outcome == Outcome::RejectEncoding)
        {
            g_rejectedEncoding++;
            LogRejectionCopy(outcome, copy, (int)copied);
        }
        else if (outcome == Outcome::RejectNull)
        {
            g_rejectedNull++;
            LogRejectionCopy(outcome, copy, (int)copied);
        }
        else
        {
            g_rejectedRange++;
            LogRejectionCopy(outcome, copy, (int)copied);
        }

        EngineTelemetry::CountTextureNameRejected();
        g_nullReturned++;
        return nullptr;
    }

    EngineTelemetry::CountTextureNameAccepted();

    // Call the engine with the validated copy: the caller's buffer may be freed
    // while the engine is still comparing, and the copy removes that window.
    char stable[kSafeCopyLength];
    memcpy(stable, copy, (size_t)length);
    stable[length] = 0;

    if (!orig_GetTexture) return nullptr;
    return orig_GetTexture(stable);
}

void* HookEntry(const char* name)
{
    return HandleCall(name);
}

} // namespace

namespace TextureLookupGuard
{

bool Install()
{
    if (orig_GetTexture) return true;
    if (SafeMode::IsEnabled())
    {
        FLog("GetTexture guard: safe mode active, hook not installed");
        return false;
    }

    CHook::InlineHook("_ZN22TextureDatabaseRuntime10GetTextureEPKc",
                      &HookEntry, &orig_GetTexture);

    const bool installed = orig_GetTexture != nullptr;
    EngineTelemetry::SetGetTextureGuardInstalled(installed);
    FLog("GetTexture guard: %s (original %p)",
         installed ? "installed" : "not installed (symbol missing)",
         (void*)orig_GetTexture);
    return installed;
}

bool Installed()
{
    return orig_GetTexture != nullptr;
}

unsigned long long RejectedCount()
{
    return g_rejectedNull + g_rejectedRange + g_rejectedEncoding;
}

unsigned long long RejectedByEncoding()
{
    return g_rejectedEncoding;
}

unsigned long long NullReturned()
{
    return g_nullReturned;
}

} // namespace TextureLookupGuard
