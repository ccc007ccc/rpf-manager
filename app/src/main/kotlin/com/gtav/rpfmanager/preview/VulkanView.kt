package com.gtav.rpfmanager.preview

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.gtav.rpfmanager.data.model.MeshData
import com.gtav.rpfmanager.data.model.MeshTexture
import kotlin.math.*

object NativeViewport {
    init {
        System.loadLibrary("rpf_viewport")
    }

    external fun create(surface: Surface): Long

    external fun destroy(handle: Long)

    external fun resize(handle: Long)

    external fun setMesh(handle: Long, vertices: FloatArray, indices: IntArray)

    external fun setTexture(handle: Long, width: Int, height: Int, pixels: IntArray?)

    external fun draw(handle: Long, matrix: FloatArray, wire: Boolean, grid: Boolean)
}

/** Single owner thread for every Vulkan call; render only after state changes. */
class VulkanView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val renderThread = HandlerThread("RpfViewport").apply { start() }
    private val renderer = Handler(renderThread.looper)
    private var handle = 0L
    private var mesh: MeshData? = null
    private var surfaceMesh: MeshData? = null
    private var texture: MeshTexture? = null
    @Volatile private var dead = false
    @Volatile private var active = true
    var onFailure: (String) -> Unit = {}
    var onOrbit: () -> Unit = {}
    var wire = false
        set(value) {
            if (field != value) {
                field = value
                requestFrame()
            }
        }

    var grid = true
        set(value) {
            if (field != value) {
                field = value
                requestFrame()
            }
        }

    private var yaw = -0.9f
    private var pitch = 0.42f
    private var distance = 4.2f
    private var panX = 0f
    private var panY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var pointerCount = 0
    private var viewMode = 0
    private val drawFrame = Runnable { drawOnThread() }
    @Volatile private var frameMatrix = FloatArray(16)
    @Volatile private var frameWire = false
    @Volatile private var frameGrid = true

    init {
        holder.addCallback(this)
        isClickable = true
        isFocusable = true
    }

    fun setScene(value: MeshData) {
        if (mesh === value) return
        mesh = value
        fit()
        renderer.post {
            if (handle != 0L)
                safely {
                    NativeViewport.setMesh(handle, value.vertices, value.indices)
                    surfaceMesh = value
                }
            drawOnThread()
        }
    }

    fun setTexture(value: MeshTexture?) {
        if (texture === value) return
        texture = value
        renderer.post {
            if (handle != 0L)
                safely {
                    NativeViewport.setTexture(
                        handle,
                        value?.width ?: 1,
                        value?.height ?: 1,
                        value?.pixels,
                    )
                }
            drawOnThread()
        }
    }

    fun fit() {
        yaw = -0.9f
        pitch = 0.42f
        distance = 4.2f
        panX = 0f
        panY = 0f
        viewMode = 0
        requestFrame()
    }

    fun view(mode: Int) {
        viewMode = mode
        when (mode) {
            0 -> {
                yaw = -0.9f
                pitch = 0.42f
            }
            1 -> {
                yaw = -(Math.PI / 2).toFloat()
                pitch = 0f
            }
            2 -> {
                yaw = -(Math.PI / 2).toFloat()
                pitch = 1.565f
            }
        }
        panX = 0f
        panY = 0f
        requestFrame()
    }

    fun resumeRendering() {
        active = true
        requestFrame()
    }

    fun pauseRendering() {
        active = false
        renderer.removeCallbacks(drawFrame)
    }

    private fun camera(): FloatArray {
        val scene = mesh ?: return FloatArray(16)
        val b = scene.bounds
        val center = FloatArray(3) { (b[it] + b[it + 3]) * 0.5f }
        val span = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2], 0.01f)
        val aspect = width.toFloat().coerceAtLeast(1f) / height.coerceAtLeast(1)
        val projection = FloatArray(16)
        if (viewMode == 0) Matrix.perspectiveM(projection, 0, 42f, aspect, 0.01f, 100f)
        else {
            val range = distance * 0.42f
            Matrix.orthoM(
                projection,
                0,
                -range * aspect,
                range * aspect,
                -range,
                range,
                0.01f,
                100f,
            )
        }
        val view = FloatArray(16)
        val radius = distance / minOf(aspect, 1f)
        Matrix.setLookAtM(
            view,
            0,
            radius * cos(pitch) * cos(yaw),
            radius * cos(pitch) * sin(yaw),
            radius * sin(pitch),
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
        )
        val pan =
            FloatArray(16).also {
                Matrix.setIdentityM(it, 0)
                Matrix.translateM(it, 0, panX, panY, 0f)
            }
        val panned = FloatArray(16).also { Matrix.multiplyMM(it, 0, pan, 0, view, 0) }
        Matrix.scaleM(panned, 0, 2f / span, 2f / span, 2f / span)
        Matrix.translateM(panned, 0, -center[0], -center[1], -center[2])
        return FloatArray(16).also { Matrix.multiplyMM(it, 0, projection, 0, panned, 0) }
    }

    fun requestFrame() {
        if (dead || !active) return
        frameMatrix = camera()
        frameWire = wire
        frameGrid = grid
        renderer.removeCallbacks(drawFrame)
        renderer.post(drawFrame)
    }

    private fun drawOnThread() {
        if (handle == 0L || surfaceMesh == null || !active) return
        safely { NativeViewport.draw(handle, frameMatrix, frameWire, frameGrid) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (handle != 0L) {
                NativeViewport.destroy(handle)
                handle = 0L
            }
            post { onFailure(message) }
        } catch (e: UnsatisfiedLinkError) {
            post { onFailure(e.message.orEmpty()) }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val scene = mesh
        val material = texture
        renderer.post {
            if (dead) return@post
            safely {
                if (handle != 0L) {
                    NativeViewport.destroy(handle)
                    handle = 0L
                }
                handle = NativeViewport.create(holder.surface)
                scene?.let {
                    NativeViewport.setMesh(handle, it.vertices, it.indices)
                    surfaceMesh = it
                }
                NativeViewport.setTexture(
                    handle,
                    material?.width ?: 1,
                    material?.height ?: 1,
                    material?.pixels,
                )
            }
            drawOnThread()
        }
        requestFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        frameMatrix = camera()
        renderer.post {
            if (handle != 0L) safely { NativeViewport.resize(handle) }
            drawOnThread()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.removeCallbacks(drawFrame)
        renderer.post {
            if (handle != 0L) NativeViewport.destroy(handle)
            handle = 0
            surfaceMesh = null
        }
    }

    fun release() {
        if (dead) return
        dead = true
        active = false
        holder.removeCallback(this)
        renderer.removeCallbacks(drawFrame)
        renderer.post {
            if (handle != 0L) NativeViewport.destroy(handle)
            handle = 0
            renderThread.quitSafely()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dead) return false
        val count = event.pointerCount
        val x = (0 until count).sumOf { event.getX(it).toDouble() }.toFloat() / count
        val y = (0 until count).sumOf { event.getY(it).toDouble() }.toFloat() / count
        val span =
            if (count >= 2) hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            else 0f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = count
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE ->
                if (count == pointerCount) {
                    if (count >= 2) {
                        if (previousSpan > 1f && span > 1f)
                            distance = (distance * previousSpan / span).coerceIn(1.3f, 24f)
                        panX += (x - previousX) / height.coerceAtLeast(1) * distance * 0.8f
                        panY -= (y - previousY) / height.coerceAtLeast(1) * distance * 0.8f
                    } else {
                        yaw -= (x - previousX) / width.coerceAtLeast(1) * 5f
                        pitch =
                            (pitch + (y - previousY) / height.coerceAtLeast(1) * 3f).coerceIn(
                                -1.5f,
                                1.565f,
                            )
                        if (viewMode != 0) onOrbit()
                        viewMode = 0
                    }
                    requestFrame()
                } else {
                    pointerCount = count
                }
            MotionEvent.ACTION_POINTER_UP -> pointerCount = -1
            MotionEvent.ACTION_UP -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        previousX = x
        previousY = y
        previousSpan = span
        return true
    }
}
