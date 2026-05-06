#include "network/TelemetryReceiver.hpp"
#include "common/protocol.hpp"
#include "common/SharedPose.hpp"
#include "video/PoseFilter.hpp"
#include <iostream>
#include <winsock2.h>

// ─────────────────────────────────────────────────────────────────────────────
// TelemetryReceiver
//
// Changes from original:
//  1. Socket receive timeout — so the calling thread can exit cleanly.
//  2. CV1 axis swizzle applied before fusion (engineY = imuZ, engineZ = -imuY).
//  3. PoseFilter (Madgwick) integration with dt from systemTimeNs.
//  4. Fused quaternion written to shared memory for the SteamVR driver.
// ─────────────────────────────────────────────────────────────────────────────

TelemetryReceiver::TelemetryReceiver(int port)
    : m_sharedHandle(nullptr), m_sharedPose(nullptr), m_lastTimestampNs(0)
{
    m_sockfd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    // 1MB OS receive buffer — absorbs CPU spikes without dropping IMU packets
    int bufferSize = 1024 * 1024;
    setsockopt(m_sockfd, SOL_SOCKET, SO_RCVBUF,
               reinterpret_cast<const char*>(&bufferSize), sizeof(bufferSize));

    // FIX: 100ms receive timeout so g_running is checked each iteration.
    // Without this the thread blocks forever and can never shut down cleanly.
    DWORD timeout = 100;
    setsockopt(m_sockfd, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&timeout), sizeof(timeout));

    sockaddr_in addr = {};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(port);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(m_sockfd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
        std::cerr << "[Telemetry] Bind failed: " << WSAGetLastError() << std::endl;
    }

    // FIX: Create shared memory block so the SteamVR driver can read poses.
    // Must be created before the telemetry thread starts writing.
    m_sharedHandle = RS_CreateSharedPose(&m_sharedPose);
    if (!m_sharedHandle) {
        std::cerr << "[Telemetry] WARNING: Failed to create shared memory — "
                     "SteamVR driver will not receive poses.\n";
    }
}

TelemetryReceiver::~TelemetryReceiver() {
    if (m_sockfd != INVALID_SOCKET) closesocket(m_sockfd);
    RS_CloseSharedPose(m_sharedHandle, m_sharedPose);
}

void TelemetryReceiver::Update() {
    ImuPacket packet;
    sockaddr_in clientAddr;
    int addrLen = sizeof(clientAddr);

    int bytesRead = recvfrom(
        m_sockfd,
        reinterpret_cast<char*>(&packet), sizeof(ImuPacket),
        0,
        reinterpret_cast<sockaddr*>(&clientAddr), &addrLen
    );

    // FIX: WSAETIMEDOUT is now expected every 100ms — not an error
    if (bytesRead == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err != WSAETIMEDOUT) {
            std::cerr << "[Telemetry] recvfrom error: " << err << std::endl;
        }
        return;
    }

    if (bytesRead == sizeof(ImuPacket)) {
        if (packet.magic == 0x52 && ValidateChecksum(packet)) {
            ProcessPacket(packet);
        } else {
            std::cerr << "[Telemetry] Packet failed validation (magic or checksum)\n";
        }
    }
}

bool TelemetryReceiver::ValidateChecksum(const ImuPacket& packet) {
    uint8_t xorSum = 0;
    const uint8_t* data = reinterpret_cast<const uint8_t*>(&packet);
    for (int i = 0; i < 44; i++) {
        xorSum ^= data[i];
    }
    return xorSum == packet.checksum;
}

void TelemetryReceiver::ProcessPacket(const ImuPacket& packet) {
    // ── Compute dt from consecutive packet timestamps ─────────────────────────
    float dt = 0.0f;
    if (m_lastTimestampNs != 0) {
        int64_t deltaNs = packet.systemTimeNs - m_lastTimestampNs;
        // Clamp dt: ignore gaps > 100ms (startup, reconnect) and negative deltas
        if (deltaNs > 0 && deltaNs < 100'000'000LL) {
            dt = static_cast<float>(deltaNs) * 1e-9f;
        }
    }
    m_lastTimestampNs = packet.systemTimeNs;
    if (dt == 0.0f) return;  // Skip first packet — no valid dt yet

    // ── CV1 axis swizzle ──────────────────────────────────────────────────────
    // The CV1 IMU does not use a standard right-hand coordinate system.
    // OpenHMD reference: src/drv_oculus_rift/rift.c
    //   engineX =  imuX   (right — unchanged)
    //   engineY =  imuZ   (up — Z becomes Y)
    //   engineZ = -imuY   (forward — Y negated becomes Z)
    float aX =  packet.accel[0];
    float aY =  packet.accel[2];
    float aZ = -packet.accel[1];

    float gX =  packet.gyro[0];
    float gY =  packet.gyro[2];
    float gZ = -packet.gyro[1];

    // ── Madgwick fusion ───────────────────────────────────────────────────────
    m_poseFilter.Update(gX, gY, gZ, aX, aY, aZ, dt);
    vr::HmdQuaternion_t q = m_poseFilter.GetQuaternion();

    // ── Write to shared memory ────────────────────────────────────────────────
    if (m_sharedPose) {
        m_sharedPose->qw          = static_cast<float>(q.w);
        m_sharedPose->qx          = static_cast<float>(q.x);
        m_sharedPose->qy          = static_cast<float>(q.y);
        m_sharedPose->qz          = static_cast<float>(q.z);
        m_sharedPose->timestampNs = packet.systemTimeNs;
        m_sharedPose->isValid     = 1;
        // Sequence last — the driver uses this as a "new data" signal
        m_sharedPose->sequence++;
    }
}
