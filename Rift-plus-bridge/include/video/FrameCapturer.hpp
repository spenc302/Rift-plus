#pragma once
#include <d3d11.h>
#include <dxgi1_2.h>

// ─────────────────────────────────────────────────────────────────────────────
// FrameCapturer — wraps DXGI Desktop Duplication.
//
// Call GetNextFrame() each encode tick. Returns an owned ID3D11Texture2D*
// that the caller MUST Release() after encoding. Returns nullptr when no new
// frame is available (DXGI timeout) or on recoverable errors.
// ─────────────────────────────────────────────────────────────────────────────

class FrameCapturer {
public:
    explicit FrameCapturer(ID3D11Device* device);
    ~FrameCapturer();

    // Returns owned texture — caller must Release(). nullptr = no new frame.
    ID3D11Texture2D* GetNextFrame();

private:
    ID3D11Device*           m_device;
    ID3D11DeviceContext*    m_context;
    IDXGIOutputDuplication* m_deskDupl;

    void InitDuplication();
};
