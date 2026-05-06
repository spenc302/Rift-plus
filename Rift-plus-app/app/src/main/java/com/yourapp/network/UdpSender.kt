package com.yourapp.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

private const val TAG = "UdpSender"

// ─────────────────────────────────────────────────────────────────────────────
// Packet format — 45 bytes, little-endian (matches ImuPacket in protocol.hpp)
//
// Offset  Size  Field
//    0     1    Magic = 0x52 ('R')
//    1     1    Version = 0x01
//    2     4    Sequence (uint32, monotonic)
//    6     4    Device timestamp µs (uint32)
//   10     8    System nanoTime (int64)
//   18     4    Accel X (float32, m/s²)
//   22     4    Accel Y (float32, m/s²)
//   26     4    Accel Z (float32, m/s²)
//   30     4    Gyro  X (float32, rad/s)
//   34     4    Gyro  Y (float32, rad/s)
//   38     4    Gyro  Z (float32, rad/s)
//   42     2    Temp (int16, units 0.01 °C)
//   44     1    XOR checksum of bytes 0–43   ← FIX: was labelled "Reserved"
//  Total: 45 bytes
// ─────────────────────────────────────────────────────────────────────────────

private const val PACKET_MAGIC    = 0x52.toByte()
private const val PACKET_VERSION  = 0x01.toByte()
private const val PACKET_SIZE     = 45

private const val OFFSET_MAGIC    = 0
private const val OFFSET_VERSION  = 1
private const val OFFSET_SEQ      = 2
private const val OFFSET_DEV_TS   = 6
private const val OFFSET_SYS_NS   = 10
private const val OFFSET_ACCEL_X  = 18
private const val OFFSET_ACCEL_Y  = 22
private const val OFFSET_ACCEL_Z  = 26
private const val OFFSET_GYRO_X   = 30
private const val OFFSET_GYRO_Y   = 34
private const val OFFSET_GYRO_Z   = 38
private const val OFFSET_TEMP     = 42
private const val OFFSET_CHECKSUM = 44   // FIX: renamed from OFFSET_RESERVED

private const val POOL_SIZE      = 32
private const val QUEUE_CAPACITY = 16

data class SenderStats(
    val packetsSent:    Long,
    val packetsDropped: Long,
    val sendErrors:     Long
)

class UdpSender(
    private val serverHost: String,
    private val serverPort: Int = 5005,
    private val scope:      CoroutineScope,
    private val onStats:    ((SenderStats) -> Unit)? = null
) {
    private val pool  = ArrayDeque<ByteArray>(POOL_SIZE)
    private val queue = Channel<ByteArray>(capacity = QUEUE_CAPACITY)

    private var sendJob: Job?            = null
    private var socket:  DatagramSocket? = null
    private var address: InetAddress?    = null

    private val packetsSent    = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)
    private val sendErrors     = AtomicLong(0)

    private var sequence = 0

    fun start() {
        repeat(POOL_SIZE) { pool.add(ByteArray(PACKET_SIZE)) }
        address = InetAddress.getByName(serverHost)
        sendJob = scope.launch(Dispatchers.IO) { runSendLoop() }
        Log.i(TAG, "UdpSender started → $serverHost:$serverPort")
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        socket?.close()
        socket = null
        pool.clear()
        Log.i(TAG, "UdpSender stopped — " +
            "sent=${packetsSent.get()} dropped=${packetsDropped.get()} errors=${sendErrors.get()}")
    }

    // Hot path — called from HID IO thread at ~1000 Hz
    fun send(
        timestampUs: Long,
        systemNanos: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX:  Float, gyroY:  Float, gyroZ:  Float,
        tempC:  Float
    ) {
        val buf = pool.poll() ?: run {
            packetsDropped.incrementAndGet()
            return
        }

        packInto(buf, timestampUs, systemNanos,
            accelX, accelY, accelZ,
            gyroX,  gyroY,  gyroZ,
            tempC)

        val result = queue.trySend(buf)
        if (result.isFailure) {
            pool.add(buf)
            packetsDropped.incrementAndGet()
        }
    }

    fun getStats() = SenderStats(packetsSent.get(), packetsDropped.get(), sendErrors.get())

    private suspend fun runSendLoop() {
        val sock = openSocket() ?: return
        val addr = address      ?: return
        val dp   = DatagramPacket(ByteArray(PACKET_SIZE), PACKET_SIZE, addr, serverPort)

        Log.i(TAG, "Send loop active → ${addr.hostAddress}:$serverPort")

        for (buf in queue) {
            if (!coroutineContext.isActive) break
            dp.setData(buf, 0, PACKET_SIZE)
            try {
                sock.send(dp)
                packetsSent.incrementAndGet()
            } catch (e: Exception) {
                sendErrors.incrementAndGet()
                Log.w(TAG, "Send error: ${e.message}")
            } finally {
                pool.add(buf)
            }
            val sent = packetsSent.get()
            if (sent % 1000L == 0L) onStats?.invoke(getStats())
        }
        sock.close()
    }

    private fun packInto(
        buf: ByteArray,
        timestampUs: Long, systemNanos: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX:  Float, gyroY:  Float, gyroZ:  Float,
        tempC:  Float
    ) {
        buf[OFFSET_MAGIC]   = PACKET_MAGIC
        buf[OFFSET_VERSION] = PACKET_VERSION

        writeInt32LE(buf,   OFFSET_SEQ,     sequence++)
        writeInt32LE(buf,   OFFSET_DEV_TS,  timestampUs.toInt())
        writeInt64LE(buf,   OFFSET_SYS_NS,  systemNanos)
        writeFloat32LE(buf, OFFSET_ACCEL_X, accelX)
        writeFloat32LE(buf, OFFSET_ACCEL_Y, accelY)
        writeFloat32LE(buf, OFFSET_ACCEL_Z, accelZ)
        writeFloat32LE(buf, OFFSET_GYRO_X,  gyroX)
        writeFloat32LE(buf, OFFSET_GYRO_Y,  gyroY)
        writeFloat32LE(buf, OFFSET_GYRO_Z,  gyroZ)

        val tempRaw = (tempC / 0.01f).toInt().toShort()
        writeInt16LE(buf, OFFSET_TEMP, tempRaw.toInt())

        // FIX: compute XOR checksum over bytes 0–43 and write at byte 44.
        // The PC-side TelemetryReceiver::ValidateChecksum() XORs bytes 0–43
        // and rejects any packet where the result doesn't match byte 44.
        // The original code wrote 0x00 here, causing EVERY packet to be dropped.
        var xor = 0
        for (i in 0 until 44) {
            xor = xor xor (buf[i].toInt() and 0xFF)
        }
        buf[OFFSET_CHECKSUM] = xor.toByte()
    }

    private fun openSocket(): DatagramSocket? {
        return try {
            DatagramSocket().also { sock ->
                sock.trafficClass  = 0x10   // IPTOS_LOWDELAY
                sock.sendBufferSize = PACKET_SIZE * 8
                socket = sock
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket open failed: ${e.message}")
            null
        }
    }

    private fun writeInt32LE(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v         and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeInt64LE(buf: ByteArray, off: Int, v: Long) {
        buf[off]     = (v         and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
        buf[off + 4] = ((v shr 32) and 0xFF).toByte()
        buf[off + 5] = ((v shr 40) and 0xFF).toByte()
        buf[off + 6] = ((v shr 48) and 0xFF).toByte()
        buf[off + 7] = ((v shr 56) and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v         and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeFloat32LE(buf: ByteArray, off: Int, v: Float) =
        writeInt32LE(buf, off, java.lang.Float.floatToRawIntBits(v))
}
