#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <winsock2.h>
#include <ws2tcpip.h>

class VideoStreamer {
public:
    VideoStreamer(const std::string& destIp, int destPort);
    ~VideoStreamer();
    void SendFrame(const std::vector<uint8_t>& frameData, bool isKeyframe);

private:
    uint32_t m_frameSeq;
    int m_sock;
    struct sockaddr_in m_dest;
};
