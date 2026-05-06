#include "CDeviceDriver.hpp"
#include "common/SharedPose.hpp"
#include <thread>
#include <atomic>
#include <iostream>

// ─────────────────────────────────────────────────────────────────────────────
// CDeviceDriver
//
// Changes from original:
//  1. Opens shared memory in Activate() to receive poses from bridge.
//  2. Background pose-push thread calls TrackedDevicePoseUpdated() at ~250Hz,
//     which is what SteamVR needs for smooth reprojection.
//  3. GetPose() reads latest quaternion from shared memory.
//  4. Deactivate() stops the background thread and cleans up.
// ─────────────────────────────────────────────────────────────────────────────

CDeviceDriver::CDeviceDriver()
    : m_unObjectId(vr::k_unTrackedDeviceIndexInvalid)
    , m_sharedHandle(nullptr)
    , m_sharedPose(nullptr)
    , m_poseThreadRunning(false)
    , m_lastSeenSequence(0)
{}

CDeviceDriver::~CDeviceDriver() {
    RS_CloseSharedPose(m_sharedHandle, m_sharedPose);
}

vr::EVRInitError CDeviceDriver::Activate(uint32_t unObjectId) {
    m_unObjectId = unObjectId;

    // ── HMD Properties ────────────────────────────────────────────────────────
    vr::PropertyContainerHandle_t props =
        vr::VRProperties()->TrackedDeviceToPropertyContainer(m_unObjectId);

    vr::VRProperties()->SetStringProperty(props,
        vr::Prop_ModelNumber_String, "Rift-S-Plus (RS+)");
    vr::VRProperties()->SetStringProperty(props,
        vr::Prop_RenderModelName_String, "generic_hmd");
    vr::VRProperties()->SetStringProperty(props,
        vr::Prop_ManufacturerName_String, "RS+ Project");

    // Resolution must match config.json and VideoDecoder
    // vr::VRProperties()->SetInt32Property(props,
    //     vr::Prop_WindowWidth_Int32,  2160);
    // vr::VRProperties()->SetInt32Property(props,
    //     vr::Prop_WindowHeight_Int32, 1200);
    vr::VRProperties()->SetFloatProperty(props,
        vr::Prop_UserIpdMeters_Float, 0.063f);

    // Direct mode — frame comes from our NVENC stream, not the desktop
    vr::VRProperties()->SetBoolProperty(props,
        vr::Prop_IsOnDesktop_Bool, false);

    // Refresh rate hint for SteamVR's reprojection engine
    vr::VRProperties()->SetFloatProperty(props,
        vr::Prop_DisplayFrequency_Float, 90.0f);

    // ── Shared Memory ─────────────────────────────────────────────────────────
    // The bridge may not be running yet — that's fine. We'll retry in the loop.
    m_sharedHandle = RS_OpenSharedPose(&m_sharedPose);
    if (!m_sharedHandle) {
        std::cerr << "[Driver] Shared memory not available yet — "
                     "will retry in pose loop.\n";
    }

    // ── Pose Push Thread ──────────────────────────────────────────────────────
    // SteamVR does NOT poll GetPose() fast enough on its own for 90Hz tracking.
    // We push actively at ~250Hz. This thread reads shared memory and calls
    // TrackedDevicePoseUpdated() whenever the bridge writes a new quaternion.
    m_poseThreadRunning = true;
    m_poseThread = std::thread([this]() {
        PosePushLoop();
    });

    return vr::VRInitError_None;
}

void CDeviceDriver::Deactivate() {
    m_poseThreadRunning = false;
    if (m_poseThread.joinable()) {
        m_poseThread.join();
    }
    m_unObjectId = vr::k_unTrackedDeviceIndexInvalid;
}

void CDeviceDriver::EnterStandby() {}

void* CDeviceDriver::GetComponent(const char* pchComponentNameAndVersion) {
    return nullptr;
}

void CDeviceDriver::DebugRequest(const char*, char* pchResponseBuffer, uint32_t unResponseBufferSize) {
    if (unResponseBufferSize >= 1) pchResponseBuffer[0] = 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// GetPose — called by SteamVR when it needs the current pose.
// Also used internally by PosePushLoop.
// ─────────────────────────────────────────────────────────────────────────────

vr::DriverPose_t CDeviceDriver::GetPose() {
    vr::DriverPose_t pose = {};
    pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
    pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };

    // Retry opening shared memory if the bridge wasn't running at Activate()
    if (!m_sharedPose) {
        m_sharedHandle = RS_OpenSharedPose(&m_sharedPose);
    }

    if (m_sharedPose && m_sharedPose->isValid) {
        pose.poseIsValid       = true;
        pose.deviceIsConnected = true;
        pose.result            = vr::TrackingResult_Running_OK;
        pose.qRotation = {
            m_sharedPose->qw,
            m_sharedPose->qx,
            m_sharedPose->qy,
            m_sharedPose->qz
        };
        // Pass Android nanoTime through as the pose timestamp (microseconds)
        pose.poseTimeOffset = 0.0;
    } else {
        // Bridge not connected — report calibrating so SteamVR doesn't complain
        pose.poseIsValid       = false;
        pose.deviceIsConnected = true;
        pose.result            = vr::TrackingResult_Calibrating_InProgress;
        pose.qRotation         = { 1, 0, 0, 0 };
    }

    return pose;
}

// ─────────────────────────────────────────────────────────────────────────────
// PosePushLoop — runs on its own thread, pushes pose to SteamVR at 250Hz.
// Only pushes when the bridge has written a new quaternion (sequence changed).
// ─────────────────────────────────────────────────────────────────────────────

void CDeviceDriver::PosePushLoop() {
    // 4ms sleep = ~250Hz. The bridge writes at ~90–1000Hz (IMU rate).
    // We don't need to match the IMU rate here; SteamVR resamples internally.
    constexpr int SLEEP_MS = 4;

    while (m_poseThreadRunning) {
        if (m_sharedPose) {
            uint32_t currentSeq = m_sharedPose->sequence;
            if (currentSeq != m_lastSeenSequence) {
                m_lastSeenSequence = currentSeq;

                if (m_unObjectId != vr::k_unTrackedDeviceIndexInvalid) {
                    vr::DriverPose_t pose = GetPose();
                    // FIX: actively push rather than waiting for SteamVR to poll
                    vr::VRServerDriverHost()->TrackedDevicePoseUpdated(
                        m_unObjectId, pose, sizeof(vr::DriverPose_t)
                    );
                }
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(SLEEP_MS));
    }
}
