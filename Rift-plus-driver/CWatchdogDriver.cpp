#include "CWatchdogDriver.hpp"
#include <openvr_driver.h>

vr::EVRInitError CWatchdogDriver::Init(vr::IVRDriverContext *pDriverContext) {
    // RS+ is a virtual device — signal SteamVR immediately so it starts up
    // without waiting for a USB event that will never come.
    vr::VRWatchdogHost()->WatchdogWakeUp(vr::TrackedDeviceClass_HMD);
    return vr::VRInitError_None;
}

void CWatchdogDriver::Cleanup() {
    // Nothing to clean up
}
