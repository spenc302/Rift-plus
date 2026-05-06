 package com.yourapp.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "HidReader"

private const val OCULUS_VENDOR_ID = 0x2833

// ─────────────────────────────────────────────────────────────────────────────
// CV1 HID Report layout (OpenHMD: src/drv_oculus_rift/rift.c)
//
// Byte  0    Report ID  (0x01 = IMU)
// Byte  1    Sample count (1 or 2 per report)
// Bytes 2–3  Total sample counter (uint16 LE)
// Bytes 4–5  Temperature (int16 LE, units 0.01 °C)
// Bytes 6–9  Device timestamp (uint32 LE, microseconds)
//
// Sample 0 at offset 10, Sample 1 at offset 34 (stride = 24 bytes)
// Within each sample:
//   +0  Accel X (int32 LE)
//   +4  Accel Y (int32 LE)
//   +8  Accel Z (int32 LE)
//   +12 Gyro  X (int32 LE)
//   +16 Gyro  Y (int32 LE)
//   +20 Gyro  Z (int32 LE)
//
// Scale factors:
//   Accel: raw * 1e-4 → m/s²
//   Gyro:  raw * 1e-4 → rad/s
//   Temp:  raw * 0.01 → °C
//
// ── CV1 COORDINATE SWIZZLE (critical for PC-side sensor fusion) ──────────────
//
// The CV1 IMU axes do NOT match a standard right-hand coordinate system.
// OpenHMD applies the following transform before feeding into the fusion filter:
//
//   Engine X  =  IMU X   (right)
//   Engine Y  =  IMU Z   (up)       ← Z becomes Y
//   Engine Z  = -IMU Y   (forward)  ← Y negated becomes Z
//
// The PC bridge must apply this same swizzle before running Madgwick/Mahony.
// If you skip this, yaw and pitch will be swapped and roll will drift badly.
//
// In the PC bridge (C# / Python), apply before fusion:
//   engineAccel = Vector3(rawAccelX,  rawAccelZ, -rawAccelY)
//   engineGyro  = Vector3(rawGyroX,   rawGyroZ,  -rawGyroY)
//
// ─────────────────────────────────────────────────────────────────────────────

private const val OFFSET_REPORT_ID     = 0
private const val OFFSET_SAMPLE_COUNT  = 1
private const val OFFSET_TEMPERATURE   = 4
private const val OFFSET_TIMESTAMP     = 6
private const val OFFSET_SAMPLE_0      = 10
private const val SAMPLE_STRIDE        = 24

private const val SAMPLE_ACCEL_X       = 0
private const val SAMPLE_ACCEL_Y       = 4
private const val SAMPLE_ACCEL_Z       = 8
private const val SAMPLE_GYRO_X        = 12
private const val SAMPLE_GYRO_Y        = 16
private const val SAMPLE_GYRO_Z        = 20

private const val ACCEL_SCALE          = 1e-4f
private const val GYRO_SCALE           = 1e-4f
private const val TEMP_SCALE           = 0.01f

private const val USB_TIMEOUT_MS       = 2
private const val HID_REPORT_SIZE      = 64

// ─────────────────────────────────────────────────────────────────────────────
// Zero-allocation callback
//
// WHY NOT ImuSample:
//   At 1000 Hz the GC would see 1000 short-lived ImuSample objects per second.
//   On Android, even minor GC pauses of 2–4ms cause visible head-tracking
//   stutters in VR. By passing primitives directly, zero heap objects are
//   allocated in the hot path.
//
// Contract for the callback:
//   - It is called on the USB IO thread.
//   - It must complete in << 1ms (just pack bytes and queue — no heavy work).
//   - It must NOT retain any references to the buffer array.
// ─────────────────────────────────────────────────────────────────────────────

typealias RawSampleCallback = (
    timestampUs: Long,
    systemNanos: Long,
    accelX: Float,
    accelY: Float,
    accelZ: Float,
    gyroX:  Float,
    gyroY:  Float,
    gyroZ:  Float,
    tempC:  Float
) -> Unit

// ─────────────────────────────────────────────────────────────────────────────
// HidReader
// ─────────────────────────────────────────────────────────────────────────────

class HidReader(
    private val connection:  UsbDeviceConnection,
    private val endpoint:    UsbEndpoint,
    private val onRawSample: RawSampleCallback,
    private val onError:     (String) -> Unit = { Log.e(TAG, it) }
) {

    // Pre-allocated report buffer — never reallocated after construction
    private val reportBuffer = ByteArray(HID_REPORT_SIZE)

    // ── Raw dump for offset verification (call before readLoop) ───────────────
    fun dumpRawReport() {
        val bytesRead = connection.bulkTransfer(
            endpoint, reportBuffer, reportBuffer.size, USB_TIMEOUT_MS
        )
        if (bytesRead > 0) {
            val hex = reportBuffer.take(bytesRead).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "RAW ($bytesRead bytes): $hex")
            Log.d(TAG, "  ReportID   = 0x${"%02X".format(reportBuffer[0])}")
            Log.d(TAG, "  SampleCount= ${reportBuffer[1].toInt() and 0xFF}")
            Log.d(TAG, "  Timestamp  = ${readUInt32(reportBuffer, OFFSET_TIMESTAMP)} µs")
            Log.d(TAG, "  Accel[0]   = ${readInt32(reportBuffer, OFFSET_SAMPLE_0 + SAMPLE_ACCEL_X)}")
            Log.d(TAG, "  Gyro[0]    = ${readInt32(reportBuffer, OFFSET_SAMPLE_0 + SAMPLE_GYRO_X)}")
        } else {
            Log.w(TAG, "dumpRawReport: no data (returned $bytesRead)")
        }
    }

    // ── Main read loop — run inside a coroutine on Dispatchers.IO ────────────
    suspend fun readLoop() = withContext(Dispatchers.IO) {
        Log.i(TAG, "HID read loop started")

        while (isActive) {
            val bytesRead = connection.bulkTransfer(
                endpoint, reportBuffer, reportBuffer.size, USB_TIMEOUT_MS
            )

            when {
                // Negative = timeout or disconnect — expected at 1ms polling
                bytesRead < 0 -> continue

                // Too short to contain even one sample
                bytesRead < OFFSET_SAMPLE_0 + SAMPLE_STRIDE -> {
                    onError("Short report: $bytesRead bytes")
                    continue
                }

                // Not an IMU report — skip silently
                reportBuffer[OFFSET_REPORT_ID] != 0x01.toByte() -> continue

                else -> parseReport(reportBuffer, bytesRead)
            }
        }

        Log.i(TAG, "HID read loop stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report parser — zero allocations
    //
    // All reads use direct array-index helpers (no ByteBuffer.wrap allocation).
    // systemNanos is captured once per report, shared across both samples.
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseReport(raw: ByteArray, length: Int) {
        val systemNanos  = System.nanoTime()
        val sampleCount  = raw[OFFSET_SAMPLE_COUNT].toInt() and 0xFF
        val tempRaw      = readInt16(raw, OFFSET_TEMPERATURE)
        val timestampUs  = readUInt32(raw, OFFSET_TIMESTAMP)
        val tempC        = tempRaw * TEMP_SCALE

        val effectiveSamples = sampleCount.coerceIn(1, 2)

        for (i in 0 until effectiveSamples) {
            val base = OFFSET_SAMPLE_0 + i * SAMPLE_STRIDE

            if (base + SAMPLE_STRIDE > length) {
                onError("Report too short for sample $i")
                break
            }

            // ── Raw values straight from the HID buffer ───────────────────────
            val accelX = readInt32(raw, base + SAMPLE_ACCEL_X) * ACCEL_SCALE
            val accelY = readInt32(raw, base + SAMPLE_ACCEL_Y) * ACCEL_SCALE
            val accelZ = readInt32(raw, base + SAMPLE_ACCEL_Z) * ACCEL_SCALE
            val gyroX  = readInt32(raw, base + SAMPLE_GYRO_X)  * GYRO_SCALE
            val gyroY  = readInt32(raw, base + SAMPLE_GYRO_Y)  * GYRO_SCALE
            val gyroZ  = readInt32(raw, base + SAMPLE_GYRO_Z)  * GYRO_SCALE

            // ── NOTE: Raw axes are sent as-is to the PC ───────────────────────
            // The PC bridge applies the CV1 swizzle before sensor fusion:
            //   engineX =  accelX   engineY =  accelZ   engineZ = -accelY
            // See the coordinate swizzle comment at the top of this file.
            // Do NOT swizzle here — keep the phone app axis-agnostic.

            onRawSample(
                timestampUs, systemNanos,
                accelX, accelY, accelZ,
                gyroX,  gyroY,  gyroZ,
                tempC
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Direct array read helpers — no ByteBuffer, no allocation
    // All values are little-endian (CV1 native byte order)
    // ─────────────────────────────────────────────────────────────────────────

    private fun readInt32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        (buf[offset + 3].toInt() shl 24)

    private fun readInt16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        (buf[offset + 1].toInt() shl 8)

    private fun readUInt32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF)) or
        ((buf[offset + 1].toLong() and 0xFF) shl 8) or
        ((buf[offset + 2].toLong() and 0xFF) shl 16) or
        ((buf[offset + 3].toLong() and 0xFF) shl 24)
}
