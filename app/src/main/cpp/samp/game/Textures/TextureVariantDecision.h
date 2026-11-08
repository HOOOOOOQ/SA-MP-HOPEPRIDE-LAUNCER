//
// Texture variant decision logic.
//
// The crash log shows a database that is reported as both "variant 5 failed to
// load" and "loaded", with sort skipped in between. The cause is not a sorting
// bug: the engine asked for a variant file that does not exist (player.etc.tmb in
// a data set that ships dxt and pvr), the load returned failure, the object was
// still published as loaded, and a later call sorted an entry array that had
// never been filled.
//
// This header is the decision table only, free of engine headers, so the rules
// can be exercised on the host:
//
//   * a variant is only usable when its .tmb, .dat and .toc all exist and have a
//     non-zero size - a truncated .tmb is the file the log says could not be
//     opened, and it must not be treated as "present";
//   * the requested variant wins when usable, because that is the format the
//     device's GPU advertises;
//   * a different variant is only chosen when the caller says swapping is proven
//     safe for this build (kAllowVariantSwitch), never silently;
//   * one database never mixes files from two variants: the choice is made once,
//     per database, from the same suffix;
//   * no variant at all means "leave the database unloaded", so the sort guard
//     has a state it can trust.
//
#pragma once

#include <cstdint>

namespace TextureVariant
{

// File kinds that make up one variant.
enum class Part
{
    Thumbs = 0,  // .tmb
    Data = 1,    // .dat
    Table = 2,   // .toc
    Count = 3
};

enum class Decision
{
    UseRequested = 0,   // requested variant is complete
    UseFallback,        // requested is missing, a proven-compatible one is used
    NoVariant,          // nothing usable: keep the database unloaded
    WantsFallback       // requested is missing and the caller may fall back
};

// Known variants, in the order they are probed. The numbers match the engine's
// texture format ids seen in the log ("format=5" for dxt).
enum Variant : uint32_t
{
    Dxt = 0,
    Pvr = 1,
    Etc = 2,
    Uncompressed = 3,
    VariantCount = 4
};

inline const char* SuffixOf(Variant variant)
{
    switch (variant)
    {
        case Dxt:          return "dxt";
        case Pvr:          return "pvr";
        case Etc:          return "etc";
        case Uncompressed: return "unc";
        case VariantCount: break;
    }
    return "";
}

// The bit set the caller passes in: for each variant, which parts exist.
struct FilesPresent
{
    // present[variant] is a bit mask of (1 << Part) for the files that exist and
    // are non-empty.
    uint32_t present[VariantCount];

    FilesPresent() { for (uint32_t i = 0; i < VariantCount; i++) present[i] = 0; }

    void Add(Variant variant, Part part)
    {
        if (variant >= VariantCount) return;
        present[variant] |= (1u << (uint32_t)part);
    }

    bool Complete(Variant variant) const
    {
        if (variant >= VariantCount) return false;
        const uint32_t needed = (1u << (uint32_t)Part::Thumbs) |
                                (1u << (uint32_t)Part::Data) |
                                (1u << (uint32_t)Part::Table);
        return (present[variant] & needed) == needed;
    }

    bool AnyPart(Variant variant) const
    {
        if (variant >= VariantCount) return false;
        return present[variant] != 0;
    }

    int CompleteCount() const
    {
        int count = 0;
        for (uint32_t i = 0; i < VariantCount; i++)
            if (Complete((Variant)i)) count++;
        return count;
    }
};

struct Plan
{
    Decision decision;
    Variant variant;        // valid for UseRequested and UseFallback
    int completeVariants;   // how many variants were complete, for the log
};

// Chooses the variant for one database.
//
// requested         what the engine asked for based on the GPU
// files             which files exist, per variant
// allowSwitch       the build has been shown to render correctly with a
//                   different variant than the GPU advertised
inline Plan Choose(Variant requested, const FilesPresent& files, bool allowSwitch)
{
    Plan plan{Decision::NoVariant, requested, files.CompleteCount()};

    if (files.Complete(requested))
    {
        plan.decision = Decision::UseRequested;
        plan.variant = requested;
        return plan;
    }

    if (!allowSwitch)
    {
        // The honest outcome: the requested variant is not there and this build
        // may not substitute another one. The database stays unloaded instead of
        // being published as loaded with no entries.
        plan.decision = files.CompleteCount() > 0 ? Decision::WantsFallback
                                                     : Decision::NoVariant;
        return plan;
    }

    // The requested variant was not usable and files are missing: only use a
    // variant that is complete, preferring the requested one first.
    if (files.Complete(requested))
    {
        plan.decision = Decision::UseRequested;
        plan.variant = requested;
        return plan;
    }

    for (uint32_t index = 0; index < VariantCount; index++)
    {
        if ((Variant)index == requested) continue;
        if (!files.Complete((Variant)index)) continue;
        plan.decision = Decision::UseFallback;
        plan.variant = (Variant)index;
        return plan;
    }

    plan.decision = Decision::NoVariant;
    return plan;
}

inline const char* Describe(Decision decision)
{
    switch (decision)
    {
        case Decision::UseRequested:  return "varian sesuai perangkat";
        case Decision::UseFallback:   return "varian pengganti (kompatibilitas terbukti)";
        case Decision::WantsFallback: return "varian diminta tidak ada, penggantian belum diizinkan";
        case Decision::NoVariant:     return "tidak ada varian lengkap, database tidak dimuat";
    }
    return "unknown";
}

// A database is only allowed to be reported as loaded when the decision produced
// a variant. This is the single rule that removes the "failed to load" followed
// by "loaded" contradiction in the log.
inline bool ShouldPublishLoaded(Decision decision)
{
    return decision == Decision::UseRequested || decision == Decision::UseFallback;
}

} // namespace TextureVariant
