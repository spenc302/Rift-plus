#pragma once
#include <cstdint>
#include <winsock2.h>
#include "common/protocol.hpp"
#include "common/SharedPose.hpp"
#include "video/PoseFilter.hpp"

class TelemetryReceiver {
public:
    explicit TelemetryReceiver(int port);
    ~TelemetryReceiver();

    // Call in a loop from the IMU thread. Returns immediately on timeout.
    void Update();

private:
    SOCKET          m_sockfd;
    HANDLE          m_sharedHandle;
    SharedPoseData* m_sharedPose;
    PoseFilter      m_poseFilter;
    int64_t         m_lastTimestampNs;

    bool ValidateChecksum(const ImuPacket& packet);
    void ProcessPacket(const ImuPacket& packet);
};
