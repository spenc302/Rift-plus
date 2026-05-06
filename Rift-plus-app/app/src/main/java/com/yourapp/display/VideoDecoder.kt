package com.yourapp.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VideoDecoder"
private const val VIDEO_PORT = 5006
private const val MAX_UDP_SIZE = 1500

class VideoDecoder(
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    private val onError: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var decodeJob: Job? = null

    // Frame Reassembly Buffer: FrameIndex -> Map of ChunkIndex to Data
    private val frameMap = ConcurrentHashMap<Int, MutableMap<Int, ByteArray>>()
    private var lastProcessedFrameId = -1

    fun start(textureId: Int, width: Int, height: Int) {
        if (isRunning.get()) return
        isRunning.set(true)

        // 1. Initialize Surface for Zero-Copy Rendering
        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            onSurfaceReady(this)
        }
        surface = Surface(surfaceTexture)

        // 2. Initialize Hardware Decoder (H.264 / AVC)
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            
            // Low-latency flags for Android MediaCodec
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            
            codec?.configure(format, surface, null, 0)
            codec?.start()
        } catch (e: Exception) {
            onError("Codec Init Failed: ${e.message}")
            return
        }

        // 3. Start Receiver Thread
        decodeJob = CoroutineScope(Dispatchers.IO).launch {
            receiverLoop()
        }
    }

    private suspend fun receiverLoop() = withContext(Dispatchers.IO) {
        socket = DatagramSocket(VIDEO_PORT).apply {
            receiveBufferSize = 2 * 1024 * 1024 // 2MB Socket Buffer
        }
        
        val receiveBuffer = ByteArray(MAX_UDP_SIZE)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

        while (isActive && isRunning.get()) {
            try {
                socket?.receive(packet)
                processChunk(packet.data, packet.length)
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Receive error: ${e.message}")
            }
        }
    }

    private fun processChunk(data: ByteArray, length: Int) {
        if (length < 14) return // Size of VideoChunkHeader

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        
        // 1. Validate "VC" Magic
        if (buffer.short != 0x5643.toShort()) return

        val frameIndex = buffer.int
        val chunkIndex = buffer.short.toInt()
        val totalChunks = buffer.short.toInt()
        val flags = buffer.get().toInt()
        buffer.get() // reserved
        val dataSize = buffer.int

        // Stability Update: If we see a much newer frame, purge stale partials
        if (frameIndex > lastProcessedFrameId + 10) {
            frameMap.clear()
        }

        // 2. Store Chunk
        val chunks = frameMap.getOrPut(frameIndex) { mutableMapOf() }
        val payload = ByteArray(dataSize)
        System.arraycopy(data, 16, payload, 0, dataSize)
        chunks[chunkIndex] = payload

        // 3. Reassemble and Feed to Decoder
        if (chunks.size == totalChunks) {
            val completeFrame = assembleFrame(chunks, totalChunks)
            feedToDecoder(completeFrame)
            frameMap.remove(frameIndex)
            lastProcessedFrameId = frameIndex
        }
    }

    private fun assembleFrame(chunks: Map<Int, ByteArray>, total: Int): ByteArray {
        var size = 0
        for (i in 0 until total) size += chunks[i]?.size ?: 0
        
        val frame = ByteArray(size)
        var offset = 0
        for (i in 0 until total) {
            val chunkData = chunks[i] ?: continue
            System.arraycopy(chunkData, 0, frame, offset, chunkData.size)
            offset += chunkData.size
        }
        return frame
    }

    private fun feedToDecoder(data: ByteArray) {
        val codecRef = codec ?: return
        try {
            val inputBufferIndex = codecRef.dequeueInputBuffer(1000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codecRef.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codecRef.queueInputBuffer(inputBufferIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }

            // Release frames to surface immediately for rendering
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codecRef.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                codecRef.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = codecRef.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder feed error: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        decodeJob?.cancel()
        socket?.close()
        codec?.stop()
        codec?.release()
        surface?.release()
        surfaceTexture?.release()
        codec = null
        frameMap.clear()
    }
}
