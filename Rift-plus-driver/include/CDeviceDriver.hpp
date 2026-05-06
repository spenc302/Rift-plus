#pragma once
#include <openvr_driver.h>
#include <thread>
#include <atomic>
#include "common/SharedPose.hpp"

class CDeviceDriver : public vr::ITrackedDeviceServerDriver {
public:
    CDeviceDriver();
    ~CDeviceDriver();

    // ITrackedDeviceServerDriver
    vr::EVRInitError Activate(uint32_t unObjectId) override;
    void Deactivate() override;
    void EnterStandby() override;
    void* GetComponent(const char* pchComponentNameAndVersion) override;
    void DebugRequest(const char*, char* pchResponseBuffer, uint32_t) override;
    vr::DriverPose_t GetPose() override;

private:
    uint32_t        m_unObjectId;

    // Shared memory — written by RSPlusBridge.exe
    HANDLE          m_sharedHandle;
    SharedPoseData* m_sharedPose;
    uint32_t        m_lastSeenSequence;

    // Pose push thread
    std::thread     m_poseThread;
    std::atomic<bool> m_poseThreadRunning;

    void PosePushLoop();
};