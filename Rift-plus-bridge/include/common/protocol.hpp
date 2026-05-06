#pragma once
#include <cstdint>

// ─────────────────────────────────────────────────────────────────────────────
// protocol.hpp — wire format shared between the Android app and the bridge.
//
// KEEP IN SYNC with UdpSender.kt (offset constants and field order must match).
//
// All multi-byte fields are little-endian.
// Total packet size: 45 bytes.
//
// Offset  Size  Field
//    0     1    Magic = 0x52 ('R')
//    1     1    Version = 0x01
//    2     4    Sequence number (uint32, monotonic, wraps at 0xFFFFFFFF)
//    6     4    Device timestamp µs (uint32, from CV1 HID report)
//   10     8    System nanoTime (int64, Android SystemClock.elapsedRealtimeNanos)
//   18     4    Accel X (float32, m/s², CV1 raw frame — swizzle in TelemetryReceiver)
//   22     4    Accel Y (float32, m/s²)
//   26     4    Accel Z (float32, m/s²)
//   30     4    Gyro  X (float32, rad/s, CV1 raw frame)
//   34     4    Gyro  Y (float32, rad/s)
//   38     4    Gyro  Z (float32, rad/s)
//   42     2    Temperature (int16, units 0.01 °C)
//   44     1    XOR checksum of bytes 0–43
// ─────────────────────────────────────────────────────────────────────────────

#pragma pack(push, 1)
struct ImuPacket {
    uint8_t  magic;           // 0x52
    uint8_t  version;         // 0x01
    uint32_t sequence;
    uint32_t deviceTimestampUs;
    int64_t  systemTimeNs;
    float    accel[3];        // x, y, z — CV1 raw frame, swizzled on receive
    float    gyro[3];         // x, y, z — CV1 raw frame, swizzled on receive
    int16_t  temperature;     // 0.01 °C units
    uint8_t  checksum;        // XOR of bytes 0–43
};
#pragma pack(pop)

static_assert(sizeof(ImuPacket) == 45, "ImuPacket must be exactly 45 bytes");

// Magic and version constants
static constexpr uint8_t PROTOCOL_MAGIC   = 0x52;
static constexpr uint8_t PROTOCOL_VERSION = 0x01;
