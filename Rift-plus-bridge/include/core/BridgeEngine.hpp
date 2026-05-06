#pragma once
#include "Common.hpp"
#include <string>

// ─────────────────────────────────────────────────────────────────────────────
// BridgeEngine — owns all subsystems and the main run loop.
//
// Usage:
//   BridgeEngine engine;
//   if (!engine.Initialize("config.json")) return 1;
//   engine.Run();   // blocks until g_running = false
//   engine.Shutdown();
// ─────────────────────────────────────────────────────────────────────────────

class TelemetryReceiver;
class VideoEncoder;
class VideoStreamer;

class BridgeEngine {
public:
    BridgeEngine();
    ~BridgeEngine();

    bool Initialize(const std::string& configPath);
    void Run();
    void Shutdown();

private:
    BridgeConfig        m_config;
    TelemetryReceiver*  m_telemetry  = nullptr;
    VideoEncoder*       m_video      = nullptr;
    VideoStreamer*       m_streamer   = nullptr;

    bool LoadConfig(const std::string& path);
};
