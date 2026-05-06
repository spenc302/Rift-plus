#include <iostream>
#include <thread>
#include <vector>
#include <windows.h>

#include "network/TelemetryReceiver.hpp"
#include "network/VideoStreamer.hpp"
#include "video/VideoEncoder.hpp"
#include "core/BridgeEngine.hpp"

// Global control for graceful shutdown

void TelemetryLoop(TelemetryReceiver* receiver) {
    std::cout << "[RS+] Telemetry Thread Started (Priority: Critical)" << std::endl;
    while (g_running) {
        receiver->Update(); // Listens for the 45-byte packets
    }
}

int main() {
    // 1. Windows Socket Initialization
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        std::cerr << "Failed to init WinSock" << std::endl;
        return -1;
    }

    // 2. Initialize Core Components
    TelemetryReceiver receiver(5005);
    VideoStreamer streamer("192.168.1.100", 5006); // Use your RedMagic IP
    VideoEncoder encoder;

    if (!encoder.Initialize()) {
        std::cerr << "Failed to init NVENC / DXGI" << std::endl;
        return -1;
    }

    // 3. Launch Telemetry Thread (High Priority for Sim-Racing)
    std::thread imuThread(TelemetryLoop, &receiver);
    SetThreadPriority(imuThread.native_handle(), THREAD_PRIORITY_TIME_CRITICAL);

    // 4. Main Video Encoding Loop
    std::cout << "[RS+] Bridge Active. Streaming at 90FPS..." << std::endl;
    
    std::vector<uint8_t> frameBuffer;
    bool isKeyframe = false;

    while (g_running) {
        if (encoder.CaptureAndEncode(frameBuffer, isKeyframe)) {
            streamer.SendFrame(frameBuffer, isKeyframe);
        }
    }

    // 5. Cleanup
    imuThread.join();
    WSACleanup();
    std::cout << "[RS+] Bridge Shutdown Cleanly." << std::endl;

    return 0;
}
