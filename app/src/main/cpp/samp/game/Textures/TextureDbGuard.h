//
// Pure decision logic for TextureDatabaseRuntime::SortEntries.
//
// Kept free of engine headers so the invariants can be tested on the host. The
// crash reports that motivated this: a fault at 0x58 with x1 = 0, and later a
// fault at 0x133f inside the same function - both look like a database pointer or
// an entry array that is not a real object any more, not like a sorting bug.
//
#pragma once

#include <cstdint>

enum class SortDecision
{
    Allow = 0,
    SkipNullDatabase,
    SkipMisalignedDatabase,
    SkipCountAboveCapacity,
    SkipAbsurdCount,
    SkipBadEntryArray,
    SkipNullNames
};

// Everything the guard needs, passed as plain values so no pointer is dereferenced
// before the decision is made.
struct SortFacts
{
    uintptr_t database;
    uint32_t  alloced;
    uint32_t  entries;
    uintptr_t entryArray;
    uint32_t  nullNames;
};

constexpr uintptr_t kMinimumPointer = 0x10000u;
constexpr uint32_t  kMaximumEntries = 0x100000u;

inline SortDecision EvaluateSortRequest(const SortFacts& facts)
{
    if (facts.database < kMinimumPointer) return SortDecision::SkipNullDatabase;
    if ((facts.database & 7u) != 0) return SortDecision::SkipMisalignedDatabase;

    // TDBArray keeps capacity >= used count. A container that breaks this was not
    // built by a completed load.
    if (facts.entries > facts.alloced) return SortDecision::SkipCountAboveCapacity;
    if (facts.entries > kMaximumEntries) return SortDecision::SkipAbsurdCount;

    if (facts.entries > 0)
    {
        if (facts.entryArray < kMinimumPointer) return SortDecision::SkipBadEntryArray;
        if ((facts.entryArray & 1u) != 0) return SortDecision::SkipBadEntryArray;
    }

    if (facts.nullNames != 0) return SortDecision::SkipNullNames;

    return SortDecision::Allow;
}

inline const char* DescribeSortDecision(SortDecision decision)
{
    switch (decision)
    {
        case SortDecision::Allow:                   return "allow";
        case SortDecision::SkipNullDatabase:        return "database pointer below the mapped range";
        case SortDecision::SkipMisalignedDatabase:  return "database pointer not aligned";
        case SortDecision::SkipCountAboveCapacity:  return "entries above capacity";
        case SortDecision::SkipAbsurdCount:         return "entry count absurd";
        case SortDecision::SkipBadEntryArray:       return "entry array not usable";
        case SortDecision::SkipNullNames:           return "entry without a name";
    }
    return "unknown";
}
