package io.github.dogkeeper886.cleancam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Draws the camera's one stream twice: on the preview, upright for the screen, and into the encoder's input surface,
 * upright for the phone's orientation and fitted into the stream's landscape size, so an upright phone's frames get
 * black bars and the PC's webcam keeps its size.
 */
class GlRenderer(private val onFrame: () -> Unit) {
    private val thread = HandlerThread("cleancam.gl").apply { start() }
    private val handler = Handler(thread.looper)

    // Used on the GL thread only.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var input: SurfaceTexture
    private var inputWidth = 1920
    private var inputHeight = 1080
    private var preview: Target? = null
    private var encoder: Target? = null
    private val textureMatrix = FloatArray(16)
    private val vertices: FloatBuffer = floatBuffer(8)
    private val texCoords: FloatBuffer = floatBuffer(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    /** Clockwise degrees that turn the camera's image upright on the screen, and in the stream. */
    @Volatile var previewRotation = 90
    @Volatile var encoderRotation = 90
    @Volatile var previewEnabled = true
    /** Off while no viewer is connected, so the encoder idles. */
    @Volatile var encoderEnabled = false

    private class Target(val surface: Surface, val egl: EGLSurface, val width: Int, val height: Int)

    /** The surface the camera draws into; its size follows [setInputSize]. */
    lateinit var inputSurface: Surface
        private set

    init {
        val ready = CountDownLatch(1)
        handler.post {
            setUpEgl()
            input = SurfaceTexture(texture)
            input.setDefaultBufferSize(inputWidth, inputHeight)
            input.setOnFrameAvailableListener({ drawFrame() }, handler)
            inputSurface = Surface(input)
            ready.countDown()
        }
        ready.await()
    }

    /** Sizes the camera's stream; call it before the camera session uses [inputSurface]. */
    fun setInputSize(width: Int, height: Int) = runOnGl {
        inputWidth = width
        inputHeight = height
        input.setDefaultBufferSize(width, height)
    }

    fun setPreview(surface: Surface?, width: Int, height: Int) = runOnGl {
        preview = replaceTarget(preview, surface, width, height)
    }

    fun setEncoder(surface: Surface?, width: Int, height: Int) = runOnGl {
        encoder = replaceTarget(encoder, surface, width, height)
    }

    /** The point on the preview, from 0 to 1 across its view, as a point on the camera's image from 0 to 1. */
    fun previewToImage(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Pair<Float, Float>? {
        val rotation = previewRotation
        val (scaleX, scaleY) = fit(rotation, viewWidth, viewHeight)
        // The image's rectangle on the view, as a share of the view.
        val u = (x - (1 - scaleX) / 2) / scaleX
        val v = (y - (1 - scaleY) / 2) / scaleY
        if (u !in 0f..1f || v !in 0f..1f) return null
        return when (rotation) {
            90 -> v to 1 - u
            180 -> 1 - u to 1 - v
            270 -> 1 - v to u
            else -> u to v
        }
    }

    private fun runOnGl(block: () -> Unit) {
        val done = CountDownLatch(1)
        handler.post { block(); done.countDown() }
        done.await()
    }

    private fun replaceTarget(old: Target?, surface: Surface?, width: Int, height: Int): Target? {
        if (old != null && old.surface == surface && old.width == width && old.height == height) return old
        old?.let {
            EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
            EGL14.eglDestroySurface(display, it.egl)
        }
        if (surface == null || !surface.isValid) return null
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == EGL14.EGL_NO_SURFACE) return null
        return Target(surface, egl, width, height)
    }

    private fun drawFrame() {
        input.updateTexImage()
        input.getTransformMatrix(textureMatrix)
        val timestamp = input.timestamp
        onFrame()
        // Each target draws and swaps while it is current.
        preview?.takeIf { previewEnabled }?.let {
            draw(it, previewRotation)
            EGL14.eglSwapBuffers(display, it.egl)
        }
        encoder?.takeIf { encoderEnabled }?.let {
            draw(it, encoderRotation)
            EGLExt.eglPresentationTimeANDROID(display, it.egl, timestamp)
            EGL14.eglSwapBuffers(display, it.egl)
        }
    }

    /** The share of the target's width and height the rotated image fills. */
    private fun fit(rotation: Int, width: Int, height: Int): Pair<Float, Float> {
        val imageAspect = if (rotation % 180 == 0) inputWidth.toFloat() / inputHeight else inputHeight.toFloat() / inputWidth
        val targetAspect = width.toFloat() / height
        return if (imageAspect > targetAspect) 1f to targetAspect / imageAspect else imageAspect / targetAspect to 1f
    }

    private fun draw(target: Target, rotation: Int) {
        EGL14.eglMakeCurrent(display, target.egl, target.egl, context)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val (scaleX, scaleY) = fit(rotation, target.width, target.height)
        vertices.position(0)
        vertices.put(floatArrayOf(-scaleX, -scaleY, scaleX, -scaleY, -scaleX, scaleY, scaleX, scaleY)).position(0)
        // A screen point samples the image turned back by the clockwise rotation, around the image's center.
        val turn = FloatArray(16)
        Matrix.setIdentityM(turn, 0)
        Matrix.translateM(turn, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(turn, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(turn, 0, -0.5f, -0.5f, 0f)
        val matrix = FloatArray(16)
        Matrix.multiplyMM(matrix, 0, textureMatrix, 0, turn, 0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // The encoder's input surface takes only a recordable config.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idleSurface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertexSource))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun floatBuffer(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
