//
// Startup stage reporting shared by native code and the Java loading screen.
//
// The Java loading screen must not invent progress. The only honest thing it can
// show is the stage the engine has actually reached, so the native side records
// the last stage it entered and Java polls it. A stage is only published from
// the code path that really performs that work.
//
// Kept header only and free of engine dependencies so the ordering rules can be
// checked on the host.
//
#pragma once

#include <cstdint>

enum GameStartupStage
{
    StageUnknown = 0,       // nothing reported yet
    StageDataCheck = 1,     // launcher verified game data (written by Java)
    StageConfig = 2,        // settings.ini written by the launcher
    StageGameInit = 3,      // CGame::InitialiseOnceBeforeRW started
    StageRenderWare = 4,    // CGame::InitialiseRenderWare entered
    StageTextureDb = 5,     // texture databases finished loading
    StageTextures = 6,      // texture database objects are ready for lookup
    StageConnect = 7,       // CNetGame started, connecting to the server
    StageReady = 8,         // engine handed control back to the game loop
};

// Stages only ever move forward: a later stage is never overwritten by an
// earlier one, because a repeated InitialiseRenderWare call would otherwise make
// the loading screen walk backwards.
inline bool ShouldAcceptStage(uint32_t current, uint32_t candidate)
{
    if (candidate <= StageUnknown || candidate >= StageReady + 1) return false;
    return candidate > current;
}
