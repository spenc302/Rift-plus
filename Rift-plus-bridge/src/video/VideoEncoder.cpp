#include "video/VideoEncoder.hpp"
#include "video/FrameCapturer.hpp"
#include "video/H264Encoder.hpp"
#include <iostream>

// ─────────────────────────────────────────────────────────────────────────────
// VideoEncoder
//
// Fixes applied:
//  1. D3D11 objects are now class members, not file-scope globals.
//  2. CaptureAndEncode() actually calls FrameCapturer → H264Encoder.
//  3. The fake IDR keyframe timer is replaced by the real IDR flag from NVENC.
// ─────────────────────────────────────────────────────────────────────────────

VideoEncoder::VideoEncoder()
    : m_device(nullptr)
    , m_context(nullptr)
    , m_capturer(nullptr)
    , m_encoder(nullptr)
{}

VideoEncoder::~VideoEncoder() {
    delete m_encoder;
    delete m_capturer;
    if (m_context) m_context->Release();
    if (m_device)  m_device->Release();
}

bool VideoEncoder::Initialize() {
    // ── 1. Create D3D11 device ────────────────────────────────────────────────
    D3D_FEATURE_LEVEL featureLevel;
    HRESULT hr = D3D11CreateDevice(
        nullptr,                    // default adapter
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr, 0, nullptr, 0,
        D3D11_SDK_VERSION,
        &m_device, &featureLevel, &m_context
    );
    if (FAILED(hr)) {
        std::cerr << "[VideoEncoder] D3D11CreateDevice failed: 0x"
                  << std::hex << hr << std::endl;
        return false;
    }

    // ── 2. Frame capturer (DXGI desktop duplication) ──────────────────────────
    m_capturer = new FrameCapturer(m_device);

    // ── 3. H.264 / NVENC encoder ──────────────────────────────────────────────
    m_encoder = new H264Encoder();
    if (!m_encoder->Initialize(m_device, 2160, 1200, /*bitrateMbps=*/25)) {
        std::cerr << "[VideoEncoder] H264Encoder init failed — "
                     "is the NVIDIA Video Codec SDK installed?\n";
        return false;
    }

    std::cout << "[VideoEncoder] Ready — 2160x1200 @ 25 Mbps H.264\n";
    return true;
}

// output is filled with Annex-B H.264 bitstream (00 00 00 01 NAL units).
// is_keyframe is set true when NVENC emits an IDR frame.
// Returns false if no new desktop frame was available this tick.
bool VideoEncoder::CaptureAndEncode(std::vector<uint8_t>& output, bool& is_keyframe) {
    // Grab the latest desktop frame — returns nullptr on timeout (no new frame)
    ID3D11Texture2D* frameTex = m_capturer->GetNextFrame();
    if (!frameTex) {
        return false;
    }

    // Encode the D3D11 texture → Annex-B bitstream via NVENC
    m_encoder->EncodeFrame(frameTex, output, is_keyframe);

    // FrameCapturer hands ownership to us — must release when done
    frameTex->Release();

    return !output.empty();
}
