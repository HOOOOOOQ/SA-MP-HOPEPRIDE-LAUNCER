//
// HopePride engine telemetry.
//
// One place that records what the engine has actually reached, so the Java
// loading screen and the launcher diagnostics can show real stages instead of an
// indeterminate spinner with a made up duration.
//
// Everything here is written from engine threads and read from Java. The values
// are plain integers: no allocation, no locks, no formatting on the write path.
// Text is formatted on demand, in the reader's thread.
//
#pragma once

#include <cstdint>

class EngineTelemetry
{
public:
    // Startup stage. Only moves forward (see GameStartupStage.h).
    static void ReportStartupStage(int stage);
    static int StartupStage();

    // Texture database counters, also printed by the native crash handler.
    static void CountTextureNameRejected();
    static void CountTextureNameAccepted();
    static void CountCustomFileRejected();
    static void CountCustomModelRegistered();
    static uint64_t TextureNamesRejected();
    static uint64_t TextureNamesAccepted();
    static uint64_t CustomFilesRejected();
    static uint64_t CustomModelsRegistered();

    // True when the guard is installed and every GetTexture call is checked.
    static void SetGetTextureGuardInstalled(bool installed);
    static bool GetTextureGuardInstalled();

    // One line per event, written to samp_log.txt by the caller. Nothing is
    // logged here so the hot path stays allocation free.
    static const char* DescribeStage(int stage);
};
