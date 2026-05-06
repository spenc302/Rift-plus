package com.yourapp.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "CameraSource"

// ─────────────────────────────────────────────────────────────────────────────
// Target capture parameters
//
// For a sim rig, the back camera points at the steering wheel and shifter.
// We want:
//   - High frame rate so the passthrough doesn't feel laggy vs the game
//   - Wide enough to see the full wheel + hands
//   - Fast shutter to avoid motion blur on fast steering inputs
//   - Resolution doesn't need to be high — 1280x720 is fine for context
//
// The RedMagic's main camera supports 120fps at reduced resolution.
// ─────────────────────────────────────────────────────────────────────────────

private val TARGET_SIZE    = Size(1280, 720)
private const val TARGET_FPS_MIN  = 60
private const val TARGET_FPS_MAX  = 90        // match Rift refresh rate
private const val OPEN_TIMEOUT_MS = 3000L     // fail fast if camera won't open

// ─────────────────────────────────────────────────────────────────────────────
// CameraSource
// ─────────────────────────────────────────────────────────────────────────────

class CameraSource(
    private val context: Context,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,  // → RiftPresentation.attachCameraTexture()
    private val onError: (String) -> Unit = { Log.e(TAG, it) }
) {

    // ── Camera2 handles ───────────────────────────────────────────────────────
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice:   CameraDevice?           = null
    private var captureSession: CameraCaptureSession?   = null

    // ── Output surface ────────────────────────────────────────────────────────
    private var outputSurfaceTexture: SurfaceTexture?   = null
    private var outputSurface: Surface?                 = null

    // ── Background thread for camera callbacks ────────────────────────────────
    private var cameraThread:  HandlerThread?   = null
    private var cameraHandler: Handler?         = null

    // ── Semaphore guards the open() call ──────────────────────────────────────
    private val openLock = Semaphore(1)

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    // glCameraTexId must be the cameraTexId allocated inside RiftRenderer.
    // Call this from the GL thread via glView.queueEvent { }.
    @SuppressLint("MissingPermission")  // permission checked in MainActivity
    fun start(glCameraTexId: Int) {
        startBackgroundThread()

        // Build SurfaceTexture on the GL thread — must happen in correct GL context
        val st = SurfaceTexture(glCameraTexId).apply {
            setDefaultBufferSize(TARGET_SIZE.width, TARGET_SIZE.height)
        }
        outputSurfaceTexture = st
        outputSurface = Surface(st)

        // Notify RiftPresentation so it calls updateTexImage() each frame
        onSurfaceReady(st)

        val cameraId = findBackCamera() ?: run {
            onError("No suitable back camera found")
            return
        }

        logCameraCapabilities(cameraId)

        if (!openLock.tryAcquire(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            onError("Timed out waiting for camera lock")
            return
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            openLock.release()
            onError("openCamera failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            openLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } finally {
            openLock.release()
        }

        outputSurface?.release()
        outputSurfaceTexture?.release()
        outputSurface = null
        outputSurfaceTexture = null

        stopBackgroundThread()
        Log.i(TAG, "CameraSource stopped")
    }

    // Adjust exposure compensation at runtime — useful for varying sim room lighting
    fun setExposureCompensation(ev: Int) {
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        try {
            val request = buildCaptureRequest(device, ev)
            session.setRepeatingRequest(request, null, cameraHandler)
            Log.d(TAG, "Exposure compensation set to EV $ev")
        } catch (e: CameraAccessException) {
            Log.w(TAG, "setExposureCompensation failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera selection
    // ─────────────────────────────────────────────────────────────────────────

    private fun findBackCamera(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun logCameraCapabilities(cameraId: String) {
        val chars  = cameraManager.getCameraCharacteristics(cameraId)
        val config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        Log.d(TAG, "Camera $cameraId — available SurfaceTexture output sizes:")
        config?.getOutputSizes(SurfaceTexture::class.java)?.forEach { Log.d(TAG, "  $it") }

        Log.d(TAG, "Camera $cameraId — available AE FPS ranges:")
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.forEach { Log.d(TAG, "  $it") }

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep  = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        Log.d(TAG, "EV range: $evRange  step: $evStep")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CameraDevice state callback
    // ─────────────────────────────────────────────────────────────────────────

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            openLock.release()
            cameraDevice = camera
            Log.i(TAG, "Camera opened: ${camera.id}")
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            openLock.release()
            camera.close()
            cameraDevice = null
            Log.w(TAG, "Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            openLock.release()
            camera.close()
            cameraDevice = null
            val reason = when (error) {
                ERROR_CAMERA_IN_USE      -> "camera already in use"
                ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
                ERROR_CAMERA_DISABLED    -> "camera disabled by policy"
                ERROR_CAMERA_DEVICE      -> "fatal camera device error"
                ERROR_CAMERA_SERVICE     -> "fatal camera service error"
                else                     -> "unknown error $error"
            }
            onError("Camera error: $reason")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture session setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun createCaptureSession(camera: CameraDevice) {
        val surface = outputSurface ?: run {
            onError("createCaptureSession called before output surface is ready")
            return
        }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                Log.i(TAG, "Capture session configured")
                startRepeatingRequest(session, camera)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError("Capture session configuration failed — check surface size vs camera capabilities")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(surface)
                val executor: Executor = Executor { cameraHandler?.post(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    sessionCallback
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(surface), sessionCallback, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            onError("createCaptureSession failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Repeating capture request
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRepeatingRequest(session: CameraCaptureSession, camera: CameraDevice) {
        try {
            val request = buildCaptureRequest(camera, ev = 0)
            session.setRepeatingRequest(request, captureCallback, cameraHandler)
            Log.i(TAG, "Repeating capture started — target ${TARGET_FPS_MIN}–${TARGET_FPS_MAX} fps")
        } catch (e: CameraAccessException) {
            onError("setRepeatingRequest failed: ${e.message}")
        }
    }

    private fun buildCaptureRequest(camera: CameraDevice, ev: Int = 0): CaptureRequest {
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {

            addTarget(outputSurface!!)

            // ── Exposure ──────────────────────────────────────────────────────
            // Auto-exposure, clamped to our FPS target
            set(CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(TARGET_FPS_MIN, TARGET_FPS_MAX))
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)

            // ── White balance ─────────────────────────────────────────────────
            // Auto — sim rooms have mixed monitor + ambient lighting
            set(CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO)

            // ── Focus ─────────────────────────────────────────────────────────
            // Continuous video AF — steering wheel is ~40cm from the phone
            set(CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            // ── Disable processing that adds latency ──────────────────────────
            // We don't need pretty pictures, just low-latency context

            set(CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF)

            set(CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_OFF)

            // OIS would fight against the raw live feed — disable it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }

            // Sharpening off — again, latency > quality for passthrough
            set(CaptureRequest.SHADING_MODE,
                CameraMetadata.SHADING_MODE_OFF)

        }.build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture callback — monitors AE convergence, then goes quiet
    // ─────────────────────────────────────────────────────────────────────────

    private var aeConverged = false

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            // Log every 90 frames (~1 second) until AE locks
            if (!aeConverged && frameNumber % 90L == 0L) {
                Log.d(TAG, "Camera running. Frame $frameNumber — waiting for AE lock")
            }
            // Mark converged after ~2 seconds (180 frames)
            if (!aeConverged && frameNumber > 180L) {
                aeConverged = true
                Log.i(TAG, "AE assumed converged at frame $frameNumber")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background thread management
    // ─────────────────────────────────────────────────────────────────────────

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraCallbackThread").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
        Log.d(TAG, "Camera background thread started")
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Camera thread join interrupted")
        }
        cameraThread  = null
        cameraHandler = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Physical mounting note
//
// Mount the RedMagic so the back camera points at the sim rig.
// For a seat-mounted holder aim roughly at the 6 o'clock position of the
// steering wheel — you want to see the rim and your hands.
//
// Luma key tuning in RiftPresentation (CompositeMode.LUMA_KEY):
//   Start with uLumaThreshold = 0.12f and adjust:
//     Lower  → more of the dark cockpit reveals your real rig
//     Higher → only the very darkest pixels become transparent
//
// Expose this as a seekbar in the debug UI so you can tune it while seated.
//
// ─────────────────────────────────────────────────────────────────────────────
// Wiring into MainActivity.kt
// ─────────────────────────────────────────────────────────────────────────────
//
//  private lateinit var cameraSource: CameraSource
//
//  // In buildComponents():
//  cameraSource = CameraSource(
//      context        = this,
//      onSurfaceReady = { st -> riftPresentation?.attachCameraTexture(st) },
//      onError        = { msg -> Log.e(TAG, "Camera: $msg") }
//  )
//
//  // start() is called from RiftPresentation's GL thread once cameraTexId exists:
//  //   glView.queueEvent { cameraSource.start(cameraTexId) }
//
//  // In onStop():
//  cameraSource.stop()
//
