package com.yourapp.display

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.os.Bundle
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Display
import android.view.WindowManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "RiftPresentation"

const val RIFT_WIDTH  = 2160
const val RIFT_HEIGHT = 1200

// ─────────────────────────────────────────────────────────────────────────────
// Vertex shader — full-screen quad, no VBO
// ─────────────────────────────────────────────────────────────────────────────

private const val VERTEX_SHADER = """
    #version 300 es
    out vec2 texCoord;

    void main() {
        vec2 positions[4] = vec2[](
            vec2(-1.0, -1.0),
            vec2( 1.0, -1.0),
            vec2(-1.0,  1.0),
            vec2( 1.0,  1.0)
        );
        vec2 uvs[4] = vec2[](
            vec2(0.0, 1.0),
            vec2(1.0, 1.0),
            vec2(0.0, 0.0),
            vec2(1.0, 0.0)
        );
        gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
        texCoord    = uvs[gl_VertexID];
    }
"""

// ─────────────────────────────────────────────────────────────────────────────
// Fragment shader — composite with screen-space gradient mask
//
// Composite modes (uMode):
//   0 = GAME_ONLY    — full immersion
//   1 = CAMERA_ONLY  — pure passthrough (rig alignment / setup)
//   2 = ALPHA_BLEND  — game alpha drives transparency
//   3 = LUMA_KEY     — dark game pixels → passthrough
//
// Screen-space mask (applied in all modes except GAME_ONLY and CAMERA_ONLY):
//   Since you're always in a fixed racing seat, the bottom 30% of the view
//   always contains your hands, wheel, and shifter. The mask additively
//   increases passthrough weight in that zone regardless of luma.
//
//   screenY: 0.0 = top of image (sky/track), 1.0 = bottom (lap/wheel)
//
//   maskWeight ramps from 0.0 at 60% height up to uMaskStrength at the bottom.
//   Default uMaskStrength = 0.6 — the bottom zone is 60% biased toward camera.
//
//   Fine-tuning:
//     uMaskStrength 0.0  → mask disabled (pure luma key only)
//     uMaskStrength 0.4  → subtle hands visibility boost
//     uMaskStrength 0.8  → bottom third is almost fully transparent
//     uMaskEdge 0.6–0.7  → where the gradient ramp begins (fraction from top)
// ─────────────────────────────────────────────────────────────────────────────

private const val FRAGMENT_SHADER = """
    #version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision mediump float;

    in vec2 texCoord;
    out vec4 fragColor;

    uniform samplerExternalOES uGameTexture;
    uniform samplerExternalOES uCameraTexture;
    uniform int   uMode;
    uniform float uLumaThreshold;   // luma key cutoff (default 0.12)
    uniform float uBlendStrength;   // alpha blend opacity (default 0.3)
    uniform float uMaskStrength;    // screen-space mask max weight (default 0.6)
    uniform float uMaskEdge;        // where the gradient begins (default 0.65)

    float luma(vec3 c) {
        return dot(c, vec3(0.299, 0.587, 0.114));
    }

    // Screen-space gradient mask
    // Returns 0.0 at the top of the frame, rising to uMaskStrength at the bottom.
    // The ramp starts at uMaskEdge (fraction from top = 0) and hits full weight at 1.0.
    float screenMask() {
        // texCoord.y: 0.0 = top (far track), 1.0 = bottom (wheel/lap)
        float screenY = texCoord.y;
        return uMaskStrength * smoothstep(uMaskEdge, 1.0, screenY);
    }

    void main() {
        vec4 game   = texture(uGameTexture,   texCoord);
        vec4 camera = texture(uCameraTexture, texCoord);

        if (uMode == 0) {
            // Pure game — no passthrough, no mask
            fragColor = game;

        } else if (uMode == 1) {
            // Pure camera — full passthrough for rig alignment
            fragColor = camera;

        } else if (uMode == 2) {
            // Alpha blend + screen-space mask
            float alpha = game.a * (1.0 - uBlendStrength) + uBlendStrength;
            float mask  = screenMask();
            // mask additively pushes toward camera in the bottom zone
            float finalWeight = clamp(alpha + mask, 0.0, 1.0);
            fragColor = mix(game, camera, finalWeight);

        } else {
            // Luma key + screen-space mask (primary sim rig mode)
            //
            // lumaKey: 1.0 = show game (bright pixels), 0.0 = show camera (dark pixels)
            float l       = luma(game.rgb);
            float lumaKey = smoothstep(uLumaThreshold - 0.1, uLumaThreshold + 0.1, l);

            // Screen mask pushes toward camera (0.0) in the bottom zone.
            // Subtract from lumaKey so bright game pixels in the bottom zone
            // still get replaced by the camera feed near the wheel.
            float mask      = screenMask();
            float finalKey  = clamp(lumaKey - mask, 0.0, 1.0);

            fragColor = mix(camera, game, finalKey);
        }
    }
"""

// ─────────────────────────────────────────────────────────────────────────────
// CompositeMode
// ─────────────────────────────────────────────────────────────────────────────

enum class CompositeMode(val glValue: Int) {
    GAME_ONLY(0),
    CAMERA_ONLY(1),
    ALPHA_BLEND(2),
    LUMA_KEY(3)
}

// ─────────────────────────────────────────────────────────────────────────────
// RiftPresentation
//
// onTexIdsReady(gameTexId, cameraTexId):
//   Fired from GL thread in onSurfaceCreated.
//   MainActivity calls videoDecoder.start(gameTexId) and cameraSource.start(cameraTexId).
//   Both deliver SurfaceTextures back via attachGameTexture / attachCameraTexture.
// ─────────────────────────────────────────────────────────────────────────────

class RiftPresentation(
    context: Context,
    display: Display,
    private val onTexIdsReady: (gameTexId: Int, cameraTexId: Int) -> Unit
) : Presentation(context, display) {

    lateinit var glView: GLSurfaceView
        private set

    private lateinit var renderer: RiftRenderer

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        renderer = RiftRenderer()

        glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContentView(glView)
        Log.i(TAG, "RiftPresentation created on: ${display.name}")
    }

    override fun onStart() { super.onStart(); glView.onResume() }
    override fun onStop()  { super.onStop();  glView.onPause()  }

    // ── Public API ────────────────────────────────────────────────────────────

    fun attachGameTexture(st: SurfaceTexture) {
        glView.queueEvent { renderer.attachGameTexture(st) }
    }

    fun attachCameraTexture(st: SurfaceTexture) {
        glView.queueEvent { renderer.attachCameraTexture(st) }
    }

    fun setCompositeMode(mode: CompositeMode)   { glView.queueEvent { renderer.setMode(mode) } }
    fun setLumaThreshold(v: Float)              { glView.queueEvent { renderer.setLumaThreshold(v) } }
    fun setBlendStrength(v: Float)              { glView.queueEvent { renderer.setBlendStrength(v) } }
    fun setMaskStrength(v: Float)               { glView.queueEvent { renderer.setMaskStrength(v) } }
    fun setMaskEdge(v: Float)                   { glView.queueEvent { renderer.setMaskEdge(v) } }

    // ─────────────────────────────────────────────────────────────────────────
    // RiftRenderer
    // ─────────────────────────────────────────────────────────────────────────

    private inner class RiftRenderer : GLSurfaceView.Renderer {

        private var programId   = 0
        private var gameTexId   = 0
        private var cameraTexId = 0

        private var gameST:   SurfaceTexture? = null
        private var cameraST: SurfaceTexture? = null

        private var uGameTexture   = -1
        private var uCameraTexture = -1
        private var uMode          = -1
        private var uLumaThreshold = -1
        private var uBlendStrength = -1
        private var uMaskStrength  = -1
        private var uMaskEdge      = -1

        // Runtime uniforms — @Volatile so queueEvent writes are visible to GL thread
        @Volatile private var mode:          CompositeMode = CompositeMode.LUMA_KEY
        @Volatile private var lumaThreshold: Float         = 0.12f
        @Volatile private var blendStrength: Float         = 0.3f
        @Volatile private var maskStrength:  Float         = 0.6f   // 60% camera bias in bottom zone
        @Volatile private var maskEdge:      Float         = 0.65f  // gradient starts at 65% down

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            logGlInfo()
            programId   = buildShaderProgram()
            gameTexId   = createExternalTexture()
            cameraTexId = createExternalTexture()
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            Log.i(TAG, "GL ready — gameTexId=$gameTexId  cameraTexId=$cameraTexId")
            onTexIdsReady(gameTexId, cameraTexId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            Log.i(TAG, "GL surface: ${width}x${height}")
        }

        override fun onDrawFrame(gl: GL10?) {
            gameST?.updateTexImage()
            cameraST?.updateTexImage()

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(programId)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, gameTexId)
            GLES30.glUniform1i(uGameTexture, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            GLES30.glUniform1i(uCameraTexture, 1)

            GLES30.glUniform1i(uMode,          mode.glValue)
            GLES30.glUniform1f(uLumaThreshold, lumaThreshold)
            GLES30.glUniform1f(uBlendStrength, blendStrength)
            GLES30.glUniform1f(uMaskStrength,  maskStrength)
            GLES30.glUniform1f(uMaskEdge,      maskEdge)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("onDrawFrame")
        }

        fun attachGameTexture(st: SurfaceTexture) {
            gameST = st
            st.attachToGLContext(gameTexId)
            Log.d(TAG, "Game ST attached → texId=$gameTexId")
        }

        fun attachCameraTexture(st: SurfaceTexture) {
            cameraST = st
            st.attachToGLContext(cameraTexId)
            Log.d(TAG, "Camera ST attached → texId=$cameraTexId")
        }

        fun setMode(m: CompositeMode)  { mode = m }
        fun setLumaThreshold(v: Float) { lumaThreshold = v }
        fun setBlendStrength(v: Float) { blendStrength = v }
        fun setMaskStrength(v: Float)  { maskStrength = v }
        fun setMaskEdge(v: Float)      { maskEdge = v }

        // ── GL helpers ────────────────────────────────────────────────────────

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private fun buildShaderProgram(): Int {
            val vert = compileShader(GLES30.GL_VERTEX_SHADER,   VERTEX_SHADER)
            val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vert)
            GLES30.glAttachShader(prog, frag)
            GLES30.glLinkProgram(prog)

            val status = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) error("Shader link:\n${GLES30.glGetProgramInfoLog(prog)}")

            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)

            uGameTexture   = GLES30.glGetUniformLocation(prog, "uGameTexture")
            uCameraTexture = GLES30.glGetUniformLocation(prog, "uCameraTexture")
            uMode          = GLES30.glGetUniformLocation(prog, "uMode")
            uLumaThreshold = GLES30.glGetUniformLocation(prog, "uLumaThreshold")
            uBlendStrength = GLES30.glGetUniformLocation(prog, "uBlendStrength")
            uMaskStrength  = GLES30.glGetUniformLocation(prog, "uMaskStrength")
            uMaskEdge      = GLES30.glGetUniformLocation(prog, "uMaskEdge")

            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val name = if (type == GLES30.GL_VERTEX_SHADER) "VERT" else "FRAG"
                error("$name compile:\n${GLES30.glGetShaderInfoLog(s)}")
            }
            return s
        }

        private fun checkGlError(label: String) {
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) Log.e(TAG, "GL error @ $label: 0x${err.toString(16)}")
        }

        private fun logGlInfo() {
            Log.i(TAG, "GL Vendor:   ${GLES30.glGetString(GLES30.GL_VENDOR)}")
            Log.i(TAG, "GL Renderer: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
            Log.i(TAG, "GL Version:  ${GLES30.glGetString(GLES30.GL_VERSION)}")
        }
    }
}
