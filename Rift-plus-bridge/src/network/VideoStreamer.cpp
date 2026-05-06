#include "network/VideoStreamer.hpp"
#include <iostream>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <chrono>

// ─────────────────────────────────────────────────────────────────────────────
// VideoStreamer — chunks encoded H.264 frames into UDP packets and sends
// them to the phone at video_port (default 5006).
//
// Packet layout per chunk (matches VideoDecoder.kt reassembly):
//   Offset  Size  Field
//      0     2    Magic = 0x5643 ('VC')
//      2     4    Frame sequence number (uint32)
//      6     2    Chunk index within this frame (uint16)
//      8     2    Total chunks in this frame (uint16)
//     10     1    Flags: bit0 = isKeyframe
//     11     2    Payload length (uint16)
//     13     N    H.264 payload (max 1387 bytes → total packet ≤ 1400)
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int    MAX_PACKET_SIZE = 1400;
static constexpr int    HEADER_SIZE     = 13;
static constexpr int    MAX_PAYLOAD     = MAX_PACKET_SIZE - HEADER_SIZE;
static constexpr uint16_t MAGIC         = 0x5643;

VideoStreamer::VideoStreamer(const std::string& destIp, int destPort)
    : m_frameSeq(0)
{
    m_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    // Small send buffer — we want to drop stale frames, not queue them
    int sndbuf = MAX_PACKET_SIZE * 4;
    setsockopt(m_sock, SOL_SOCKET, SO_SNDBUF,
               reinterpret_cast<const char*>(&sndbuf), sizeof(sndbuf));

    // DSCP EF (0xB8) — highest QoS for video on the local network
    int tos = 0xB8;
    setsockopt(m_sock, IPPROTO_IP, IP_TOS,
               reinterpret_cast<const char*>(&tos), sizeof(tos));

    memset(&m_dest, 0, sizeof(m_dest));
    m_dest.sin_family = AF_INET;
    m_dest.sin_port   = htons(destPort);
    inet_pton(AF_INET, destIp.c_str(), &m_dest.sin_addr);

    std::cout << "[VideoStreamer] Ready → " << destIp << ":" << destPort << "\n";
}

VideoStreamer::~VideoStreamer() {
    if (m_sock != INVALID_SOCKET) closesocket(m_sock);
}

void VideoStreamer::SendFrame(const std::vector<uint8_t>& data, bool isKeyframe) {
    if (data.empty()) return;

    const int totalChunks = static_cast<int>(
        (data.size() + MAX_PAYLOAD - 1) / MAX_PAYLOAD
    );

    uint8_t packet[MAX_PACKET_SIZE];

    for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
        const int offset      = chunkIdx * MAX_PAYLOAD;
        const int payloadSize = static_cast<int>(
            std::min((int)data.size() - offset, MAX_PAYLOAD)
        );

        // Build header
        packet[0]  = (MAGIC >> 8) & 0xFF;
        packet[1]  =  MAGIC       & 0xFF;
        packet[2]  = (m_frameSeq >> 24) & 0xFF;
        packet[3]  = (m_frameSeq >> 16) & 0xFF;
        packet[4]  = (m_frameSeq >> 8)  & 0xFF;
        packet[5]  =  m_frameSeq        & 0xFF;
        packet[6]  = (chunkIdx  >> 8) & 0xFF;
        packet[7]  =  chunkIdx        & 0xFF;
        packet[8]  = (totalChunks >> 8) & 0xFF;
        packet[9]  =  totalChunks       & 0xFF;
        packet[10] = isKeyframe ? 0x01 : 0x00;
        packet[11] = (payloadSize >> 8) & 0xFF;
        packet[12] =  payloadSize       & 0xFF;

        memcpy(packet + HEADER_SIZE, data.data() + offset, payloadSize);

        int sent = sendto(
            m_sock,
            reinterpret_cast<const char*>(packet),
            HEADER_SIZE + payloadSize,
            0,
            reinterpret_cast<sockaddr*>(&m_dest),
            sizeof(m_dest)
        );
        if (sent == SOCKET_ERROR) {
            std::cerr << "[VideoStreamer] sendto error: " << WSAGetLastError() << "\n";
        }
    }

    m_frameSeq++;
}
