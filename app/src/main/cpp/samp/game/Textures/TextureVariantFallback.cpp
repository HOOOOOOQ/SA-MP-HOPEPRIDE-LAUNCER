//
// Texture database variant fallback. See TextureVariantFallback.h.
//

#include "TextureVariantFallback.h"
#include "../common.h"
#include "../../main.h"
#include "../../vendor/shadowhook/patch.h"
#include "TextureDatabase.h"
#include "TextureDbGuard.h"
#include "../SafeMode.h"

#include <unistd.h>
#include <cstdio>

// Read by the native crash handler, so these need external linkage.
volatile unsigned long long g_texVariantFallbacks = 0;
volatile unsigned long long g_texVariantLoadFailures = 0;
volatile unsigned long long g_texDbSortSkips = 0;

// ---------------------------------------------------------------------------
// Database snapshots.
//
// Preformatted at load time, in normal thread context, so the signal handler only
// has to print a string. That keeps the crash path free of pointer chasing: a
// database that died halfway through loading is exactly the object you must not
// dereference while handling a SIGSEGV.
// ---------------------------------------------------------------------------
namespace {

constexpr int kMaxTrackedDatabases = 12;
constexpr int kNameLength = 24;
constexpr unsigned long kDiagCapacity = 3072;

struct DatabaseSnapshot {
    char name[kNameLength];
    unsigned int alloced;
    unsigned int entries;
    unsigned long long dataPtr;
    int format;
    int state;          // 0 unknown, 1 loaded, 2 failed, 3 sort skipped
    char note[64];
};

DatabaseSnapshot g_snapshots[kMaxTrackedDatabases];
int g_snapshotCount = 0;
char g_diagText[kDiagCapacity];

void CopyName(char* destination, const char* source) {
    int i = 0;
    if (source) {
        while (i < kNameLength - 1 && source[i]) {
            destination[i] = source[i];
            i++;
        }
    }
    destination[i] = 0;
}

DatabaseSnapshot* FindOrAddSnapshot(const char* name) {
    if (!name) return nullptr;

    for (int i = 0; i < g_snapshotCount; i++) {
        bool same = true;
        for (int k = 0; k < kNameLength - 1; k++) {
            if (g_snapshots[i].name[k] != name[k]) { same = false; break; }
            if (!name[k]) break;
        }
        if (same) return &g_snapshots[i];
    }

    if (g_snapshotCount >= kMaxTrackedDatabases) return nullptr;

    DatabaseSnapshot* slot = &g_snapshots[g_snapshotCount++];
    *slot = DatabaseSnapshot{};
    CopyName(slot->name, name);
    slot->state = 0;
    return slot;
}

void SetNote(DatabaseSnapshot* slot, const char* note) {
    if (!slot || !note) return;
    int i = 0;
    while (i < (int)sizeof(slot->note) - 1 && note[i]) {
        slot->note[i] = note[i];
        i++;
    }
    slot->note[i] = 0;
}

void RebuildDiagnosticsText() {
    unsigned long used = 0;
    const int written = snprintf(g_diagText, sizeof(g_diagText), "texdb-state: tracked=%d skips=%llu\n",
                                 g_snapshotCount, g_texDbSortSkips);
    if (written > 0) used = (unsigned long)written;

    for (int i = 0; i < g_snapshotCount && used + 160 < sizeof(g_diagText); i++) {
        const DatabaseSnapshot& slot = g_snapshots[i];
        const int line = snprintf(g_diagText + used, sizeof(g_diagText) - used,
                                  "  db %-12s state=%d format=%d alloced=%u entries=%u data=%llx %s\n",
                                  slot.name, slot.state, slot.format, slot.alloced, slot.entries,
                                  slot.dataPtr, slot.note);
        if (line <= 0) break;
        used += (unsigned long)line;
    }
}

} // namespace

// Called by the native crash handler: returns text that is already formatted.
extern "C" const char* TextureDbDiagnostics_Text() {
    return g_diagText;
}

namespace
{

typedef bool (*LoadThumbsFn)(void* thiz, int format, bool setEntries);

LoadThumbsFn orig_LoadThumbs = nullptr;

// The engine asks for one variant only; these are tried in order of how widely
// the mobile renderer supports them when the requested one is not on disk.
const int kVariantOrder[] =
{
    DF_UNC,     // plain RGBA, always decodable, no RLE step
    DF_ETC,     // ETC1, present in most mobile data sets
    DF_DXT,     // S3TC / DXT
    DF_PVR,     // PowerVR
    DF_360,     // Xbox 360 packed
    DF_PS3,     // PS3 packed
};

const char* SuffixForFormat(int format)
{
    switch (format)
    {
        case DF_UNC: return "unc";
        case DF_DXT: return "dxt";
        case DF_360: return "360";
        case DF_PS3: return "ps3";
        case DF_PVR: return "pvr";
        case DF_ETC: return "etc";
        default:     return nullptr;
    }
}

// TextureDatabase lays out the vtable first, so the database name follows it.
const char* DatabaseName(void* thiz)
{
    if (!thiz) return nullptr;

    const uintptr_t offset = sizeof(uintptr_t);
    const char* name = *reinterpret_cast<const char**>(reinterpret_cast<uintptr_t>(thiz) + offset);
    if (!name) return nullptr;

    // Only accept something that looks like a plain directory name, so a wrong
    // layout cannot send us into a wild pointer.
    for (int i = 0; i < 64; i++)
    {
        const unsigned char c = static_cast<unsigned char>(name[i]);
        if (c == 0) return (i > 0) ? name : nullptr;
        if (c < 0x20 || c > 0x7E || c == '/' || c == '\\') return nullptr;
    }
    return nullptr;
}

bool VariantExists(const char* database, int format)
{
    const char* suffix = SuffixForFormat(format);
    if (!suffix || !database || !g_pszStorage) return false;

    char path[512];
    const int written = snprintf(path, sizeof(path), "%s/texdb/%s/%s.%s.tmb",
                                 g_pszStorage, database, database, suffix);
    if (written <= 0 || static_cast<size_t>(written) >= sizeof(path)) return false;

    return access(path, R_OK) == 0;
}

// Forcing a different variant than the engine asked for is NOT safe: doing that
// for the "samp" database made the engine load unc data under the metadata of
// the requested variant and it died inside TextureDatabaseRuntime::SortEntries
// with a null entry (crash_native: fault 0x58, x1 = 0). The engine is only
// switched to another variant when the requested one is genuinely absent and
// the replacement carries a matching file set.
constexpr bool kForceVariant = false;

struct VariantFiles
{
    bool tmb = false;
    bool dat = false;
    bool toc = false;
    bool Any() const { return tmb || dat || toc; }
    bool Complete() const { return tmb; }
};

bool ProbeVariant(const char* database, int format, VariantFiles& files)
{
    files = VariantFiles{};
    const char* suffix = SuffixForFormat(format);
    if (!suffix || !database || !g_pszStorage) return false;

    const char* extensions[] = { "tmb", "dat", "toc" };
    bool* flags[] = { &files.tmb, &files.dat, &files.toc };

    for (int i = 0; i < 3; i++)
    {
        char path[512];
        const int written = snprintf(path, sizeof(path), "%s/texdb/%s/%s.%s.%s",
                                     g_pszStorage, database, database, suffix, extensions[i]);
        if (written <= 0 || static_cast<size_t>(written) >= sizeof(path)) continue;
        *flags[i] = access(path, R_OK) == 0;
    }

    return files.Any();
}

// Mixing variants is what breaks the database: LoadThumbs would hand the engine
// the thumb list of variant B while the runtime object was created for variant
// A, and the resulting entries carry null fields - which is exactly the crash
// still reported (SortEntries, fault 0x58, x1 = 0) in a build that logged
// "variant-fallback=1". The engine's own choice is therefore always honoured;
// the hook only reports what is on disk so the data set can be judged.
constexpr bool kAllowVariantSwitch = false;

int PickExistingVariant(const char* database, int requested)
{
    if (requested >= DF_Default) return requested;

    VariantFiles requestedFiles;
    ProbeVariant(database, requested, requestedFiles);

    if (requestedFiles.Any()) return requested;

    for (int candidate : kVariantOrder)
    {
        if (candidate == requested) continue;

        VariantFiles candidateFiles;
        if (!ProbeVariant(database, candidate, candidateFiles)) continue;
        if (!candidateFiles.Complete()) continue;

        if (requestedFiles.dat && !candidateFiles.dat) continue;
        if (requestedFiles.toc && !candidateFiles.toc) continue;

        if (kAllowVariantSwitch)
        {
            g_texVariantFallbacks++;
            FLog("texdb '%s': variant %d is absent, using variant %d", database, requested, candidate);
            return candidate;
        }

        FLog("texdb '%s': variant %d is absent, variant %d would be available (switch disabled)",
             database, requested, candidate);
        return requested;
    }

    return requested;
}

bool LoadThumbsHook(void* thiz, int format, bool setEntries)
{
    static thread_local bool inHook = false;
    if (!orig_LoadThumbs) return false;
    if (inHook) return orig_LoadThumbs(thiz, format, setEntries);

    inHook = true;

    const char* database = DatabaseName(thiz);
    int chosen = format;

    if (database)
    {
        chosen = PickExistingVariant(database, format);
        if (chosen != format)
        {
            g_texVariantFallbacks++;
            FLog("texdb '%s': variant %d (%s) not on disk, loading variant %d (%s) instead",
                 database, format, SuffixForFormat(format) ? SuffixForFormat(format) : "auto",
                 chosen, SuffixForFormat(chosen) ? SuffixForFormat(chosen) : "auto");
        }
        else
        {
            FLog("texdb '%s': variant %d (%s) available", database, format,
                 SuffixForFormat(format) ? SuffixForFormat(format) : "auto");
        }
    }

    const bool result = orig_LoadThumbs(thiz, chosen, setEntries);

    if (!result)
    {
        g_texVariantLoadFailures++;
        FLog("texdb '%s': variant %d failed to load", database ? database : "?", chosen);
    }

    // Snapshot the container while we are still in normal thread context: the
    // crash handler prints this instead of walking a database that may be dead.
    DatabaseSnapshot* slot = FindOrAddSnapshot(database);
    if (slot)
    {
        slot->state = result ? 1 : 2;
        slot->format = chosen;
        slot->dataPtr = 0;
        slot->entries = 0;
        slot->alloced = 0;

        const uintptr_t base = reinterpret_cast<uintptr_t>(thiz);
        if (base >= 0x10000 && (base & 7) == 0)
        {
            const TextureDatabase* db = static_cast<const TextureDatabase*>(thiz);
            slot->alloced = db->entries.numAlloced;
            slot->entries = db->entries.numEntries;
            const uintptr_t baseEntries = reinterpret_cast<uintptr_t>(db->entries.dataPtr);
            slot->dataPtr = static_cast<unsigned long long>(baseEntries);
        }

        SetNote(slot, result ? "load ok" : "load failed");
        RebuildDiagnosticsText();
    }

    inHook = false;
    return result;
}

// TextureDatabaseRuntime::Load(withName, fullyLoad, forcedFormat) runs before
// any database is touched, so it is the second chance to pick a variant that
// exists when the LoadThumbs hook is unavailable in this build.
typedef void* (*LoadFn)(const char* withName, bool fullyLoad, int forcedFormat);
LoadFn orig_Load = nullptr;

void* LoadHook(const char* withName, bool fullyLoad, int forcedFormat)
{
    if (!orig_Load) return nullptr;

    int chosen = forcedFormat;
    if (withName)
    {
        chosen = PickExistingVariant(withName, forcedFormat);
        if (chosen != forcedFormat)
        {
            g_texVariantFallbacks++;
            FLog("texdb '%s': load variant %d instead of %d", withName, chosen, forcedFormat);
        }
    }

    void* result = orig_Load(withName, fullyLoad, chosen);

    if (!result)
    {
        DatabaseSnapshot* slot = FindOrAddSnapshot(withName);
        if (slot)
        {
            slot->state = 2;
            SetNote(slot, "Load returned null");
            RebuildDiagnosticsText();
            FLog("texdb '%s': TextureDatabaseRuntime::Load returned null", withName);
        }
    }

    return result;
}

// One shot dump of what the installed data set actually contains, database by
// database and variant by variant. This is the evidence needed to tell a
// missing variant apart from a corrupt one, without guessing.
void LogDatabaseInventory()
{
    if (!g_pszStorage)
    {
        FLog("texdb inventory: storage not resolved yet, skipped");
        return;
    }

    const char* databases[] = { "samp", "mobile", "txd", "gta3", "gta_int", "cutscene", "player", "menu" };

    for (const char* database : databases)
    {
        char line[512] = {};
        size_t used = 0;
        bool found = false;

        for (int format : kVariantOrder)
        {
            VariantFiles files;
            if (!ProbeVariant(database, format, files)) continue;
            if (!files.Any()) continue;

            found = true;
            const int written = snprintf(line + used, sizeof(line) - used, " %s(tmb=%d dat=%d toc=%d)",
                                         SuffixForFormat(format) ? SuffixForFormat(format) : "?",
                                         (int)files.tmb, (int)files.dat, (int)files.toc);
            if (written <= 0 || static_cast<size_t>(written) >= sizeof(line) - used) break;
            used += static_cast<size_t>(written);
        }

        if (found) FLog("texdb '%s' variants:%s", database, line);
        else       FLog("texdb '%s': no variant files on disk", database);
    }
}

// ---------------------------------------------------------------------------
// TextureDatabaseRuntime::SortEntries(bool)
//
// crash_native.txt faults inside this function at address +0x58 with x1 = 0,
// i.e. while touching a null entry of the database it is sorting. The entry
// array comes from the database load, so a database that loaded with a
// mismatched variant (or not at all) hands the sort a container with null
// slots. The guard below inspects the container first and refuses to sort a
// container that would crash, which keeps the engine alive while leaving every
// healthy database sorted exactly as before.
// ---------------------------------------------------------------------------
typedef bool (*SortEntriesFn)(void* thiz, bool arg);

SortEntriesFn orig_SortEntries = nullptr;

// The container layout is not guessed: TextureDatabase.h describes it and
// VALIDATE_SIZE pins the size (0xA8 on arm64, 0x78 on armv7), so the guard reads
// the real fields through the real types instead of raw offsets.
bool SortEntriesHook(void* thiz, bool arg)
{
    if (!orig_SortEntries) return false;

    const uintptr_t raw = reinterpret_cast<uintptr_t>(thiz);

    // The engine hands this function a database pointer. When a database failed to
    // load, that pointer can be a leftover or a partially built object: the report
    // with fault 0x133f is what dereferencing a small garbage value looks like.
    // Alignment and range are checked before the first field is read.
    if (raw < kMinimumPointer || (raw & 7u) != 0)
    {
        g_texDbSortSkips++;
        FLog("SortEntries: implausible database pointer %llx, skipped",
             (unsigned long long)raw);
        RebuildDiagnosticsText();
        return false;
    }

    TextureDatabase* database = static_cast<TextureDatabase*>(thiz);

    const char* name = database->name;
    const uintptr_t rawName = reinterpret_cast<uintptr_t>(name);
    if (name && (rawName < kMinimumPointer || (rawName & 1u) != 0 || name[0] == 0))
    {
        name = nullptr;
    }

    SortFacts facts{};
    facts.database = raw;
    facts.alloced = database->entries.numAlloced;
    facts.entries = database->entries.numEntries;
    facts.entryArray = reinterpret_cast<uintptr_t>(database->entries.dataPtr);

    // A database whose load already failed is never sorted: the container did not
    // finish filling in, and sorting is an optimisation, not a requirement.
    if (name)
    {
        for (int i = 0; i < g_snapshotCount; i++)
        {
            if (g_snapshots[i].state != 2) continue;
            bool same = true;
            for (int k = 0; k < kNameLength - 1; k++)
            {
                if (g_snapshots[i].name[k] != name[k]) { same = false; break; }
                if (!name[k]) break;
            }
            if (same)
            {
                g_texDbSortSkips++;
                FLog("SortEntries: '%s' failed to load earlier, sort skipped", name);
                g_snapshots[i].state = 3;
                SetNote(&g_snapshots[i], "load failed, sort skipped");
                RebuildDiagnosticsText();
                return false;
            }
        }
    }

    const uintptr_t rawEntries = facts.entryArray;
    const bool arrayUsable = facts.entries == 0 ||
            (rawEntries >= kMinimumPointer && (rawEntries & 1u) == 0);

    uint32_t nullNames = 0;
    uint32_t firstBadIndex = 0;
    if (arrayUsable && facts.entries > 0 && facts.entries <= facts.alloced &&
        facts.entries <= kMaximumEntries)
    {
        TextureDatabaseEntry* entries = database->entries.dataPtr;
        for (uint32_t i = 0; i < facts.entries; i++)
        {
            if (!entries[i].name)
            {
                if (nullNames == 0) firstBadIndex = i;
                nullNames++;
            }
        }
    }
    facts.nullNames = nullNames;

    const SortDecision decision = EvaluateSortRequest(facts);
    if (decision != SortDecision::Allow)
    {
        g_texDbSortSkips++;
        FLog("SortEntries: '%s' skipped (%s) alloced=%u entries=%u data=%llx firstBad=%u",
             name ? name : "?", DescribeSortDecision(decision),
             facts.alloced, facts.entries, (unsigned long long)facts.entryArray, firstBadIndex);

        DatabaseSnapshot* slot = FindOrAddSnapshot(name);
        if (slot)
        {
            slot->state = 3;
            slot->alloced = facts.alloced;
            slot->entries = facts.entries;
            slot->dataPtr = facts.entryArray;
            SetNote(slot, DescribeSortDecision(decision));
            RebuildDiagnosticsText();
        }
        return false;
    }

    return orig_SortEntries(thiz, arg);
}

} // namespace

bool TextureVariantFallback::DatabaseFilesPresent(const char* database)
{
    if (!database) return false;

    for (int format : kVariantOrder)
    {
        VariantFiles files;
        if (ProbeVariant(database, format, files) && files.Any()) return true;
    }

    return false;
}

void TextureVariantFallback::Initialise()
{
    // Safe mode: the previous start-up did not survive, so none of the optional
    // patches are installed and the engine runs exactly as it shipped. The
    // inventory is still written, because that is what the report needs.
    if (SafeMode::IsEnabled())
    {
        FLog("SafeMode: active (%s), texture database hooks not installed", SafeMode::ActiveMarker());
        LogDatabaseInventory();
        return;
    }

    CHook::InlineHook("_ZN15TextureDatabase10LoadThumbsE21TextureDatabaseFormatb",
                      LoadThumbsHook, &orig_LoadThumbs);

    CHook::InlineHook("_ZN22TextureDatabaseRuntime4LoadEPKcb21TextureDatabaseFormat",
                      LoadHook, &orig_Load);

    CHook::InlineHook("_ZN22TextureDatabaseRuntime11SortEntriesEb",
                      SortEntriesHook, &orig_SortEntries);

    LogDatabaseInventory();

    FLog("TextureVariantFallback: LoadThumbs hook %s, Load hook %s, force variant=%d",
         orig_LoadThumbs ? "installed" : "not installed (symbol missing)",
         orig_Load ? "installed" : "not installed (symbol missing)",
         (int)kForceVariant);
    FLog("TextureVariantFallback: variant switching %s",
         kAllowVariantSwitch ? "enabled" : "disabled (log only)");
    FLog("TextureVariantFallback: SortEntries guard %s",
         orig_SortEntries ? "installed" : "not installed (symbol missing)");
}
