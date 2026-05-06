#include <openvr_driver.h>
#include "CDeviceDriver.hpp"
#include "CWatchdogDriver.hpp"

using namespace vr;

CDeviceDriver g_deviceDriver;
CWatchdogDriver g_watchdogDriver;

extern "C" __declspec(dllexport) void* HmdDriverFactory(const char* pInterfaceName, int* pReturnCode) {
    if (0 == strcmp(IServerTrackedDeviceProvider_Version, pInterfaceName)) {
        return &g_deviceDriver;
    }
    if (0 == strcmp(IVRWatchdogProvider_Version, pInterfaceName)) {
        return &g_watchdogDriver;
    }

    if (pReturnCode) *pReturnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
