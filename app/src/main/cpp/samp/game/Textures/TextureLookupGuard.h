//
// TextureDatabaseRuntime::GetTexture guard - public entry points.
//
// Header is deliberately free of engine and hooking headers so the launcher side
// and the host tests can include it without dragging in libGTASA.
//
#pragma once

namespace TextureLookupGuard
{
    // Installs the guard. Safe to call more than once; returns true when the
    // engine function was resolved and the hook is in place.
    bool Install();

    // True when every GetTexture call goes through the guard.
    bool Installed();

    // Counters, read by the native crash handler so a report says whether the
    // crash happened on a name the guard already rejected.
    unsigned long long RejectedCount();
    unsigned long long RejectedByEncoding();
    unsigned long long NullReturned();
}
