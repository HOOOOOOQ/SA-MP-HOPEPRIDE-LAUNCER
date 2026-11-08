//
// TextureDatabaseRuntime::GetTexture guard.
//
// Evidence (crash_native.txt, crash 1):
//   pc/lr  _ZN22TextureDatabaseRuntime10GetTextureEPKc
//   fault  0x116d91a38     (freed / unmapped heap)
//   x0     0x45b6468e      (small, aligned like a length, not a pointer)
//   x1     0                (the const char* name argument)
//   x2     0x6a006f73      (UTF-16 "so" - "s\0o\0" read as one 32 bit value)
//
// Read that together: the engine is inside its own name comparison loop and the
// string it was handed is either null, already freed, or not a char string at
// all. Neither the loop nor the comparator is wrong - the *argument* is.
//
// So the guard does not touch engine offsets. It questions the argument:
//   * the name pointer must be non null and above the minimum mapped address;
//   * the bytes must be a plain ASCII name that terminates inside a bounded
//     window, which is what rejects a UTF-16 or freed buffer;
//   * the read must be safe, which is decided by probing /proc/self/maps before
//     the first byte is touched. A probe that cannot be answered counts as
//     unsafe, so the guard fails closed instead of faulting.
//
// The name read itself is also performed on a local copy so the engine never
// re-walks a buffer that may be unmapped half a microsecond later.
//
#pragma once

#include <cstdint>

namespace GetTextureGuard
{

enum class Outcome
{
    Pass = 0,       // the name is usable, call the original
    RejectNull,     // null or empty
    RejectRange,    // below the mapped range, or not readable
    RejectEncoding, // bytes that cannot be a texture name
    Unavailable     // the guard could not decide (fails closed)
};

// Copy limit. Texture names in the shipped data sets are short; anything longer
// than this is not a name the engine would find anyway.
constexpr int kSafeCopyLength = 128;

// A NUL terminated ASCII name of length 1..kSafeCopyLength-1.
inline Outcome ClassifyBytes(const unsigned char* bytes, int available, int& lengthOut)
{
    lengthOut = 0;
    if (!bytes || available <= 0) return Outcome::RejectNull;

    // A UTF-16 name reaches this point as "s\0o\0j\0..." and the first byte alone
    // is printable, so the encoding has to be detected before the terminator is
    // accepted: a NUL at an odd index followed by more text is UTF-16LE, which the
    // engine never produces and would read as a one character name.
    if (available >= 4 && bytes[1] == 0 && bytes[2] != 0 && bytes[3] == 0)
        return Outcome::RejectEncoding;

    for (int index = 0; index < available && index < kSafeCopyLength; index++)
    {
        const unsigned char value = bytes[index];
        if (value == 0)
        {
            if (index == 0) return Outcome::RejectNull;
            lengthOut = index;
            return Outcome::Pass;
        }
        // Printable ASCII only.
        if (value < 0x20 || value > 0x7E) return Outcome::RejectEncoding;
    }

    // No terminator inside the window: not a name, or a longer buffer than any
    // texture in the database.
    return Outcome::RejectEncoding;
}

inline const char* Describe(Outcome outcome)
{
    switch (outcome)
    {
        case Outcome::Pass:           return "ok";
        case Outcome::RejectNull:     return "nama texture null/kosong";
        case Outcome::RejectRange:    return "nama texture tidak dapat dibaca";
        case Outcome::RejectEncoding: return "nama texture bukan string ASCII";
        case Outcome::Unavailable:    return "guard nama texture tidak tersedia";
    }
    return "unknown";
}

// Byte span check used by the /proc/self/maps probe. Returns true when
// [start, start+length) is fully inside [mappedStart, mappedEnd).
inline bool SpanCovers(uintptr_t mappedStart, uintptr_t mappedEnd,
                       uintptr_t start, uintptr_t length)
{
    if (length == 0) return false;
    if (mappedEnd <= mappedStart) return false;
    if (start < mappedStart) return false;
    // Written without addition so a near-overflow pointer cannot wrap into a
    // false positive.
    const uintptr_t available = mappedEnd - start;
    return available >= length;
}

} // namespace GetTextureGuard
