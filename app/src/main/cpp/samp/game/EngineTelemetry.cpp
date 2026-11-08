#include "EngineTelemetry.h"
#include "Textures/GameStartupStage.h"

#include <atomic>

namespace {

std::atomic<uint32_t> g_startupStage{StageUnknown};
std::atomic<uint64_t> g_textureNamesRejected{0};
std::atomic<uint64_t> g_textureNamesAccepted{0};
std::atomic<uint64_t> g_customFilesRejected{0};
std::atomic<uint64_t> g_customModelsRegistered{0};
std::atomic<bool>     g_getTextureGuardInstalled{false};

} // namespace

void EngineTelemetry::ReportStartupStage(int stage)
{
    const uint32_t candidate = (uint32_t)stage;
    uint32_t current = g_startupStage.load(std::memory_order_relaxed);
    while (ShouldAcceptStage(current, candidate))
    {
        if (g_startupStage.compare_exchange_weak(current, candidate,
                                                 std::memory_order_release,
                                                 std::memory_order_relaxed))
        {
            return;
        }
    }
}

int EngineTelemetry::StartupStage()
{
    return (int)g_startupStage.load(std::memory_order_acquire);
}

void EngineTelemetry::CountTextureNameRejected()
{
    g_textureNamesRejected.fetch_add(1, std::memory_order_relaxed);
}

void EngineTelemetry::CountTextureNameAccepted()
{
    g_textureNamesAccepted.fetch_add(1, std::memory_order_relaxed);
}

void EngineTelemetry::CountCustomFileRejected()
{
    g_customFilesRejected.fetch_add(1, std::memory_order_relaxed);
}

void EngineTelemetry::CountCustomModelRegistered()
{
    g_customModelsRegistered.fetch_add(1, std::memory_order_relaxed);
}

uint64_t EngineTelemetry::TextureNamesRejected()
{
    return g_textureNamesRejected.load(std::memory_order_relaxed);
}

uint64_t EngineTelemetry::TextureNamesAccepted()
{
    return g_textureNamesAccepted.load(std::memory_order_relaxed);
}

uint64_t EngineTelemetry::CustomFilesRejected()
{
    return g_customFilesRejected.load(std::memory_order_relaxed);
}

uint64_t EngineTelemetry::CustomModelsRegistered()
{
    return g_customModelsRegistered.load(std::memory_order_relaxed);
}

void EngineTelemetry::SetGetTextureGuardInstalled(bool installed)
{
    g_getTextureGuardInstalled.store(installed, std::memory_order_release);
}

bool EngineTelemetry::GetTextureGuardInstalled()
{
    return g_getTextureGuardInstalled.load(std::memory_order_acquire);
}

const char* EngineTelemetry::DescribeStage(int stage)
{
    switch (stage)
    {
        case StageUnknown:      return "Belum mulai";
        case StageDataCheck:    return "Memeriksa data game";
        case StageConfig:       return "Memuat konfigurasi";
        case StageGameInit:     return "Menyiapkan game";
        case StageRenderWare:   return "Menyiapkan RenderWare";
        case StageTextureDb:    return "Memuat texture database";
        case StageTextures:     return "Menyiapkan tekstur";
        case StageConnect:      return "Menghubungkan ke server";
        case StageReady:        return "Masuk ke game";
    }
    return "Belum mulai";
}
