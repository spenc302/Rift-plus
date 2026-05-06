#pragma once
#include <d3d11.h>
#include <vector>
#include <cstdint>

// Forward-declare NVENC types so this header compiles without the SDK
struct NV_ENCODE_API_FUNCTION_LIST;

// ─────────────────────────────────────────────────────────────────────────────
// H264Encoder — NVENC H.264 encoder.
//
// Initialize() once with the shared D3D11 device.
// EncodeFrame() each tick — fills outBitstream with Annex-B NAL units.
// ─────────────────────────────────────────────────────────────────────────────

class H264Encoder {
public:
    H264Encoder();
    ~H264Encoder();

    bool Initialize(ID3D11Device* pDevice, int width, int height, int bitrateMbps);
    void EncodeFrame(ID3D11Texture2D* pTexture,
                     std::vector<uint8_t>& outBitstream,
                     bool& isKeyframe);

private:
    NV_ENCODE_API_FUNCTION_LIST* m_nvenc;
    void*  m_encoder;
    void*  m_bitstreamBuffer;
    int    m_width;
    int    m_height;
    uint64_t m_frameCount;
};
