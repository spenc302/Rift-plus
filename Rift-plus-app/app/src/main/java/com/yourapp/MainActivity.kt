package com.yourapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yourapp.display.CameraSource
import com.yourapp.display.RiftPresentation
import com.yourapp.display.VideoDecoder
import com.yourapp.network.UdpSender
import com.yourapp.usb.HidReader
import com.yourapp.usb.RiftUsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG        = "RSPlus_Main"
private const val SERVER_IP  = "192.168.1.100"   // TODO: load from config/preferences

class MainActivity : AppCompatActivity() {

    // ── Core components ───────────────────────────────────────────────────────
    private lateinit var riftUsbManager:  RiftUsbManager
    private lateinit var udpSender:       UdpSender
    private lateinit var videoDecoder:    VideoDecoder
    private lateinit var cameraSource:    CameraSource    // FIX: was never instantiated
    private var          hidReader:       HidReader? = null
    private var          riftPresentation: RiftPresentation? = null

    private val isStarted = AtomicBoolean(false)
    private lateinit var statusText: TextView

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText = findViewById(R.id.tvStatus)

        initializeComponents()
        requestNeededPermissions()
    }

    override fun onStop() {
        super.onStop()
        stopAllServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllServices()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Component initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializeComponents() {

        // ── 1. UDP sender (IMU → PC) ──────────────────────────────────────────
        // FIX: UdpSender requires a CoroutineScope as 3rd argument.
        // lifecycleScope is correct — it cancels automatically with the Activity.
        udpSender = UdpSender(
            serverHost = SERVER_IP,
            serverPort = 5005,
            scope      = lifecycleScope
        )

        // ── 2. USB / HID manager ──────────────────────────────────────────────
        riftUsbManager = RiftUsbManager(
            context = this,
            onConnectionReady = { connection, usbInterface ->
                // FIX: the connection + interface go to HidReader, not UdpSender.
                // UdpSender.start() takes no parameters — it only needs a scope.
                udpSender.start()

                // HidReader reads the CV1 HID reports and fires onRawSample
                // at ~1000 Hz. The lambda packs raw primitives into UDP packets.
                // FIX: HidReader was never instantiated in the original code.
                val endpoint = usbInterface.getEndpoint(0)  // interrupt IN endpoint
                hidReader = HidReader(
                    connection  = connection,
                    endpoint    = endpoint,
                    onRawSample = { ts, ns, ax, ay, az, gx, gy, gz, temp ->
                        // This runs on the HID IO thread — must be fast (it is).
                        udpSender.send(ts, ns, ax, ay, az, gx, gy, gz, temp)
                    },
                    onError = { msg -> updateStatus("HID Error: $msg") }
                )
                // Launch the read loop on an IO coroutine tied to the Activity
                lifecycleScope.launch(Dispatchers.IO) {
                    hidReader?.readLoop()
                }
                updateStatus("HMD Connected & Streaming")
            },
            onDisconnect = {
                hidReader = null
                stopAllServices()
                updateStatus("HMD Disconnected")
            },
            onError = { msg -> updateStatus("USB Error: $msg") }
        )

        // ── 3. Video decoder (PC → phone) ─────────────────────────────────────
        videoDecoder = VideoDecoder(
            onSurfaceReady = { surfaceTexture ->
                riftPresentation?.attachGameTexture(surfaceTexture)
            },
            onError = { msg -> updateStatus("Video Error: $msg") }
        )

        // ── 4. Camera source (passthrough) ────────────────────────────────────
        // FIX: CameraSource was never instantiated in the original code.
        // It is started later from the GL thread once cameraTexId is available
        // (see setupPresentation → onTexIdsReady callback).
        cameraSource = CameraSource(
            context        = this,
            onSurfaceReady = { st -> riftPresentation?.attachCameraTexture(st) },
            onError        = { msg -> updateStatus("Camera Error: $msg") }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Presentation (HDMI output → Rift CV1 screen)
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupPresentation() {
        val dm       = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (displays.isNotEmpty()) {
            // FIX: RiftPresentation constructor requires onTexIdsReady as 3rd arg.
            // This callback is fired from the GL thread once the EGL context is
            // ready and both external texture IDs have been allocated.
            riftPresentation = RiftPresentation(
                context      = this,
                display      = displays[0],
                onTexIdsReady = { gameTexId, cameraTexId ->
                    // FIX: use the real GL-allocated texture IDs, not hardcoded 1.
                    // videoDecoder binds its MediaCodec output surface to gameTexId.
                    videoDecoder.start(
                        textureId = gameTexId,
                        width     = 2160,
                        height    = 1200
                    )
                    // CameraSource must be started from the GL thread
                    // because it allocates a SurfaceTexture on that context.
                    riftPresentation?.glView?.queueEvent {
                        cameraSource.start(cameraTexId)
                    }
                }
            )
            riftPresentation?.show()
            updateStatus("HDMI Output Active")
        } else {
            updateStatus("Waiting for HDMI/Rift Connection...")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start / stop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAllServices() {
        if (isStarted.getAndSet(true)) return
        setupPresentation()
        riftUsbManager.start()
    }

    private fun stopAllServices() {
        if (!isStarted.getAndSet(false)) return
        Log.i(TAG, "Stopping all services...")
        hidReader = null
        videoDecoder.stop()
        udpSender.stop()
        cameraSource.stop()
        riftUsbManager.stop()
        riftPresentation?.dismiss()
        riftPresentation = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startAllServices()
        } else {
            updateStatus("Permissions required: Camera + USB")
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            startAllServices()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateStatus(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { statusText.text = msg }
    }
}
