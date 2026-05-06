#pragma once
// SharedPose.hpp
// Shared memory block written by RSPlusBridge.exe, read by driver_rsplus.dll.
//
// Both processes must include this header.
// The bridge creates the mapping; the driver opens it.
// No locking needed — the driver treats the data as best-effort:
// if sequence hasn't changed since last read, it reuses the previous pose.
//
// Placement: copy this header into both projects under include/common/

#pragma once
#include <cstdint>
#include <windows.h>

#define RS_SHARED_NAME  L"Local\\RSPlus_Pose_v1"
#define RS_SHARED_SIZE  sizeof(SharedPoseData)

#pragma pack(push, 1)
struct SharedPoseData {
    // Incremented atomically by bridge on every write.
    // Driver compares to last-seen value to skip redundant pose pushes.
    volatile uint32_t sequence;

    // Fused quaternion (w, x, y, z) — engine coordinate frame
    // Already has CV1 swizzle applied: engineY = imuZ, engineZ = -imuY
    volatile float    qw, qx, qy, qz;

    // Source timestamp from the IMU packet (Android system nanoTime)
    // Used by SteamVR for prediction — do not substitute GetTickCount()
    volatile int64_t  timestampNs;

    // Set to 1 once bridge has written at least one valid frame.
    // Driver returns TrackingResult_Calibrating_InProgress until this is 1.
    volatile uint8_t  isValid;

    uint8_t  _pad[3];   // alignment
};
#pragma pack(pop)

// ─────────────────────────────────────────────────────────────────────────────
// Bridge side — call once at startup before the telemetry thread starts
// ─────────────────────────────────────────────────────────────────────────────
inline HANDLE RS_CreateSharedPose(SharedPoseData** outPtr) {
    HANDLE hMap = CreateFileMappingW(
        INVALID_HANDLE_VALUE, nullptr,
        PAGE_READWRITE, 0, RS_SHARED_SIZE,
        RS_SHARED_NAME
    );
    if (!hMap) {
        return nullptr;
    }
    *outPtr = reinterpret_cast<SharedPoseData*>(
        MapViewOfFile(hMap, FILE_MAP_WRITE, 0, 0, RS_SHARED_SIZE)
    );
    if (!*outPtr) {
        CloseHandle(hMap);
        return nullptr;
    }
    // Zero-initialise so isValid starts at 0
    memset(*outPtr, 0, RS_SHARED_SIZE);
    return hMap;
}

// ─────────────────────────────────────────────────────────────────────────────
// Driver side — call in CDeviceDriver::Activate()
// ─────────────────────────────────────────────────────────────────────────────
inline HANDLE RS_OpenSharedPose(SharedPoseData** outPtr) {
    HANDLE hMap = OpenFileMappingW(FILE_MAP_READ, FALSE, RS_SHARED_NAME);
    if (!hMap) {
        return nullptr;   // Bridge not running yet — driver will retry in pose loop
    }
    *outPtr = reinterpret_cast<SharedPoseData*>(
        MapViewOfFile(hMap, FILE_MAP_READ, 0, 0, RS_SHARED_SIZE)
    );
    if (!*outPtr) {
        CloseHandle(hMap);
        return nullptr;
    }
    return hMap;
}

// ─────────────────────────────────────────────────────────────────────────────
// Cleanup — call on shutdown in both processes
// ─────────────────────────────────────────────────────────────────────────────
inline void RS_CloseSharedPose(HANDLE hMap, SharedPoseData* ptr) {
    if (ptr)  UnmapViewOfFile(ptr);
    if (hMap) CloseHandle(hMap);
}
