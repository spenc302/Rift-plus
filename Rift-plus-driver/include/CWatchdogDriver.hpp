#pragma once
#include <openvr_driver.h>

// ─────────────────────────────────────────────────────────────────────────────
// CWatchdogDriver — wakes SteamVR when the HMD is detected.
//
// SteamVR calls Init() on startup. We simply signal immediately since
// RS+ is a virtual device — there's no physical USB to watch for.
// ─────────────────────────────────────────────────────────────────────────────

class CWatchdogDriver : public vr::IVRWatchdogProvider {
public:
    vr::EVRInitError Init(vr::IVRDriverContext *pDriverContext) override;
    void Cleanup() override;
};
