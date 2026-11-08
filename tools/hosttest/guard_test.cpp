// Host tests for the TextureDatabaseRuntime::SortEntries decision logic.
// The header is pure C++, so the invariants that guard the engine call can be
// exercised without a device.
#include <cstdio>
#include <cstdint>

#include "TextureDbGuard.h"

static int g_failures = 0;

static void Check(bool condition, const char* what)
{
    printf("%s  %s\n", condition ? "PASS" : "FAIL", what);
    if (!condition) g_failures++;
}

static SortFacts MakeFacts(uintptr_t database, uint32_t alloced, uint32_t entries,
                           uintptr_t entryArray, uint32_t nullNames)
{
    SortFacts facts{};
    facts.database = database;
    facts.alloced = alloced;
    facts.entries = entries;
    facts.entryArray = entryArray;
    facts.nullNames = nullNames;
    return facts;
}

int main()
{
    const uintptr_t heap = 0x7f00000000ull;

    Check(EvaluateSortRequest(MakeFacts(heap, 100, 10, heap + 0x1000, 0)) == SortDecision::Allow,
          "healthy database is sorted");

    Check(EvaluateSortRequest(MakeFacts(heap, 0, 0, 0, 0)) == SortDecision::Allow,
          "empty database is allowed (nothing to sort)");

    const SortDecision small = EvaluateSortRequest(MakeFacts(0x133f, 0, 0, 0, 0));
    Check(small == SortDecision::SkipNullDatabase || small == SortDecision::SkipMisalignedDatabase,
          "the 0x133f value from the crash report is rejected before any field is read");

    Check(EvaluateSortRequest(MakeFacts(0x40, 0, 0, 0, 0)) == SortDecision::SkipNullDatabase,
          "a pointer below the mapped range is rejected");

    Check(EvaluateSortRequest(MakeFacts(heap, 5, 9, heap + 0x1000, 0)) == SortDecision::SkipCountAboveCapacity,
          "entries above capacity means the load never completed");

    Check(EvaluateSortRequest(MakeFacts(heap, 0x300000, 0x300000, heap + 0x1000, 0)) == SortDecision::SkipAbsurdCount,
          "an absurd entry count is rejected");

    Check(EvaluateSortRequest(MakeFacts(heap, 100, 10, 0x800, 0)) == SortDecision::SkipBadEntryArray,
          "a tiny entry array pointer is rejected");

    Check(EvaluateSortRequest(MakeFacts(heap, 100, 10, heap + 0x1001, 0)) == SortDecision::SkipBadEntryArray,
          "an unaligned entry array pointer is rejected");

    Check(EvaluateSortRequest(MakeFacts(heap, 100, 10, heap + 0x1000, 3)) == SortDecision::SkipNullNames,
          "entries without a name are refused");

    Check(EvaluateSortRequest(MakeFacts(heap, 100, 10, 0, 0)) == SortDecision::SkipBadEntryArray,
          "a null entry array with entries to sort is refused");

    printf("\n%s (%d failure(s))\n", g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures);
    return g_failures == 0 ? 0 : 1;
}
