#include "core/BridgeEngine.hpp"
#include "network/TelemetryReceiver.hpp"
#include "network/VideoStreamer.hpp"
#include "video/VideoEncoder.hpp"
#include "core/Common.hpp"
#include <iostream>
#include <thread>
#include <fstream>

// Simple JSON field extraction — avoids a full JSON library dependency.
// Only handles the flat string/int/bool fields in config.json.
static std::string ExtractString(const std::string& json, const std::string& key) {
    auto pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "";
    pos = json.find(':', pos) + 1;
    while (json[pos] == ' ' || json[pos] == '"') pos++;
    auto end = json.find_first_of("\",}", pos);
    return json.substr(pos, end - pos);
}
static int ExtractInt(const std::string& json, const std::string& key, int def = 0) {
    auto s = ExtractString(json, key);
    return s.empty() ? def : std::stoi(s);
}
static bool ExtractBool(const std::string& json, const std::string& key, bool def = false) {
    auto s = ExtractString(json, key);
    return s.empty() ? def : (s == "true");
}

BridgeEngine::BridgeEngine() = default;

BridgeEngine::~BridgeEngine() {
    Shutdown();
}

bool BridgeEngine::Initialize(const std::string& configPath) {
    // ── Load config ───────────────────────────────────────────────────────────
    if (!LoadConfig(configPath)) {
        std::cerr << "[Bridge] Could not load " << configPath
                  << " — using defaults\n";
    }

    std::cout << "[Bridge] Config:"
              << "\n  headset_ip  = " << m_config.headsetIp
              << "\n  imu_port    = " << m_config.imuPort
              << "\n  video_port  = " << m_config.videoPort
              << "\n  resolution  = " << m_config.videoWidth
                                      << "x" << m_config.videoHeight
              << "\n  fps         = " << m_config.videoFps
              << "\n  bitrate     = " << m_config.bitrateMbps << " Mbps\n";

    // ── Telemetry (IMU receive from phone) ────────────────────────────────────
    // TelemetryReceiver constructor also creates the shared memory block
    // that the SteamVR driver reads poses from.
    m_telemetry = new TelemetryReceiver(m_config.imuPort);

    // ── Video encoder (DXGI capture + NVENC) ─────────────────────────────────
    m_video = new VideoEncoder();
    if (!m_video->Initialize()) {
        std::cerr << "[Bridge] VideoEncoder init failed — video disabled\n";
        delete m_video;
        m_video = nullptr;
    }

    // ── Video streamer (UDP to phone) ─────────────────────────────────────────
    m_streamer = new VideoStreamer(m_config.headsetIp, m_config.videoPort);

    return true;
}

void BridgeEngine::Run() {
    std::cout << "[Bridge] Running. Press Ctrl+C to stop.\n";

    // IMU receive thread — calls TelemetryReceiver::Update() in a tight loop.
    // Update() blocks for up to 100ms waiting for a UDP packet, so this thread
    // stays at near-zero CPU when the phone is not connected.
    std::thread imuThread([this]() {
        while (g_running) {
            m_telemetry->Update();
        }
    });

    // Video encode/stream thread — runs at target FPS
    std::thread videoThread([this]() {
        using clock = std::chrono::steady_clock;
        const auto frameDuration = std::chrono::microseconds(
            1'000'000 / m_config.videoFps
        );

        while (g_running) {
            auto frameStart = clock::now();

            if (m_video && m_streamer) {
                std::vector<uint8_t> encodedData;
                bool isKeyframe = false;

                if (m_video->CaptureAndEncode(encodedData, isKeyframe)) {
                    m_streamer->SendFrame(encodedData, isKeyframe);
                }
            }

            // Sleep for remainder of frame budget
            auto elapsed = clock::now() - frameStart;
            if (elapsed < frameDuration) {
                std::this_thread::sleep_for(frameDuration - elapsed);
            }
        }
    });

    imuThread.join();
    videoThread.join();
}

void BridgeEngine::Shutdown() {
    std::cout << "[Bridge] Shutting down...\n";
    delete m_telemetry; m_telemetry = nullptr;
    delete m_video;     m_video     = nullptr;
    delete m_streamer;  m_streamer  = nullptr;
}

bool BridgeEngine::LoadConfig(const std::string& path) {
    std::ifstream f(path);
    if (!f.is_open()) return false;

    std::string json((std::istreambuf_iterator<char>(f)),
                      std::istreambuf_iterator<char>());

    m_config.headsetIp      = ExtractString(json, "headset_ip");
    m_config.imuPort        = ExtractInt(json, "imu_port",     5005);
    m_config.videoPort      = ExtractInt(json, "video_port",   5006);
    m_config.videoWidth     = ExtractInt(json, "width",        2160);
    m_config.videoHeight    = ExtractInt(json, "height",       1200);
    m_config.videoFps       = ExtractInt(json, "fps",          90);
    m_config.bitrateMbps    = ExtractInt(json, "bitrate_mbps", 25);
    m_config.hapticsEnabled = ExtractBool(json, "haptics_enabled", true);

    return !m_config.headsetIp.empty();
}
