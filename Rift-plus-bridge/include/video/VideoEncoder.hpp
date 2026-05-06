#pragma once
#include <d3d11.h>
#include <vector>
#include <cstdint>

class FrameCapturer;
class H264Encoder;

// ─────────────────────────────────────────────────────────────────────────────
// VideoEncoder — coordinates FrameCapturer (DXGI) and H264Encoder (NVENC).
//
// Call Initialize() once.
// Call CaptureAndEncode() each frame tick from BridgeEngine's video thread.
// ─────────────────────────────────────────────────────────────────────────────

class VideoEncoder {
public:
    VideoEncoder();
    ~VideoEncoder();

    bool Initialize();

    // Returns false if no new desktop frame was available this tick.
    // outBitstream contains Annex-B H.264 on success.
    bool CaptureAndEncode(std::vector<uint8_t>& outBitstream, bool& isKeyframe);

private:
    ID3D11Device*        m_device;
    ID3D11DeviceContext* m_context;
    FrameCapturer*       m_capturer;
    H264Encoder*         m_encoder;
};
