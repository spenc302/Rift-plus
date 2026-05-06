#include "video/FrameCapturer.hpp"
#include <iostream>

FrameCapturer::FrameCapturer(ID3D11Device* device)
    : m_device(device)
    , m_context(nullptr)
    , m_deskDupl(nullptr)
{
    m_device->GetImmediateContext(&m_context);
    InitDuplication();
}

FrameCapturer::~FrameCapturer() {
    if (m_deskDupl) {
        m_deskDupl->Release();
        m_deskDupl = nullptr;
    }
    if (m_context) {
        m_context->Release();
        m_context = nullptr;
    }
}

void FrameCapturer::InitDuplication() {
    IDXGIDevice* dxgiDevice = nullptr;
    m_device->QueryInterface(__uuidof(IDXGIDevice), (void**)&dxgiDevice);

    IDXGIAdapter* adapter = nullptr;
    dxgiDevice->GetParent(__uuidof(IDXGIAdapter), (void**)&adapter);

    IDXGIOutput* output = nullptr;
    adapter->EnumOutputs(0, &output); // Captures Primary Monitor

    IDXGIOutput1* output1 = nullptr;
    output->QueryInterface(__uuidof(IDXGIOutput1), (void**)&output1);

    // This is the magic call for high-speed capture
    output1->DuplicateOutput(m_device, &m_deskDupl);

    // Cleanup temporary interfaces
    output1->Release();
    output->Release();
    adapter->Release();
    dxgiDevice->Release();
}

ID3D11Texture2D* FrameCapturer::GetNextFrame() {
    IDXGIResource* desktopRes = nullptr;
    DXGI_OUTDUPL_FRAME_INFO frameInfo;

    HRESULT hr = m_deskDupl->AcquireNextFrame(10, &frameInfo, &desktopRes);
    if (FAILED(hr)) return nullptr;

    ID3D11Texture2D* frameTex = nullptr;
    desktopRes->QueryInterface(__uuidof(ID3D11Texture2D), (void**)&frameTex);

    m_deskDupl->ReleaseFrame();
    desktopRes->Release();

    return frameTex;
}
