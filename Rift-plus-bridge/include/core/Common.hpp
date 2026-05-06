#pragma once
#include <cstdint>
#include <atomic>
#include <string>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
// Common.hpp — shared types and globals for RSPlusBridge
// ─────────────────────────────────────────────────────────────────────────────

// Global running flag — set to false by signal handler to shut everything down.
// TelemetryReceiver, BridgeEngine, and main loop all check this.
inline std::atomic<bool> g_running{ true };

// Video frame passed from VideoEncoder → VideoStreamer
struct EncodedFrame {
    std::vector<uint8_t> data;
    bool                 isKeyframe = false;
    int64_t              timestampMs = 0;
};

// Bridge config loaded from config.json
struct BridgeConfig {
    std::string headsetIp     = "192.168.1.100";
    int         imuPort       = 5005;
    int         videoPort     = 5006;
    int         videoWidth    = 2160;
    int         videoHeight   = 1200;
    int         videoFps      = 90;
    int         bitrateMbps   = 25;
    bool        hapticsEnabled = true;
};
