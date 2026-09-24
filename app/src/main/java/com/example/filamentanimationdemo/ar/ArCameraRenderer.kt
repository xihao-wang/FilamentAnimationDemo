package com.example.filamentanimationdemo.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.View
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Owns only the ARCore camera feed, plane hit tests, and Anchor lifecycle. */
internal class ArCameraRenderer(
    private val cameraView: View,
    private val frameStateStore: ArFrameStateStore,
    private val onStatusChanged: (ArStatus) -> Unit,
    private val onDiagnosticsChanged: (ArDiagnostics) -> Unit,
    private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    private val pendingTaps = ConcurrentLinkedQueue<Tap>()
    private val quadVertices = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val cameraTexCoords = allocateFloatBuffer(FloatArray(8))
    private var cameraTextureId = 0
    private var shaderProgram = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var configuredSession: Session? = null
    private var anchor: Anchor? = null
    private var lastStatus: ArStatus? = null
    private var lastDiagnostics: ArDiagnostics? = null
    private var loggedFirstFrame = false
    private var lastDisplayRotation: Int? = null

    fun queueTap(x: Float, y: Float) {
        pendingTaps.clear()
        pendingTaps.offer(Tap(x, y))
    }

    fun releaseAnchor() {
        anchor?.detach()
        anchor = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        cameraTextureId = createExternalTexture()
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        configuredSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val currentSession = session ?: return

        try {
            if (configuredSession !== currentSession) {
                currentSession.setCameraTextureName(cameraTextureId)
                configuredSession = currentSession
            }
            val displayRotation = cameraView.display?.rotation ?: Surface.ROTATION_0
            currentSession.setDisplayGeometry(
                displayRotation,
                surfaceWidth,
                surfaceHeight
            )
            if (lastDisplayRotation != displayRotation) {
                lastDisplayRotation = displayRotation
                Log.i(
                    TAG,
                    "Display geometry: rotation=$displayRotation, " +
                        "size=${surfaceWidth}x$surfaceHeight"
                )
            }

            val frame = currentSession.update()
            if (frame.timestamp != 0L) {
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true
                    Log.i(TAG, "Session.update() produced the first camera frame")
                }
                updateCameraTexCoords(frame)
                drawCameraBackground()
            }

            val camera = frame.camera
            val planes = currentSession.getAllTrackables(Plane::class.java)
            publishDiagnostics(
                ArDiagnostics(
                    cameraTrackingState = camera.trackingState.name,
                    trackingFailureReason = camera.trackingFailureReason.name,
                    totalPlaneCount = planes.size,
                    trackingPlaneCount = planes.count {
                        it.trackingState == TrackingState.TRACKING
                    },
                    horizontalUpwardPlaneCount = planes.count {
                        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                    },
                    trackedHorizontalPlaneCount = planes.count {
                        it.trackingState == TrackingState.TRACKING &&
                            it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                    }
                )
            )
            if (camera.trackingState != TrackingState.TRACKING) {
                publishStatus(ArStatus.SEARCHING)
                return
            }

            handleTap(frame)
            val projection = FloatArray(16)
            val view = FloatArray(16)
            camera.getProjectionMatrix(projection, 0, NEAR_CLIP_METERS, FAR_CLIP_METERS)
            camera.getViewMatrix(view, 0)

            val anchorMatrix = anchor
                ?.takeIf { it.trackingState == TrackingState.TRACKING }
                ?.pose
                ?.let { pose -> FloatArray(16).also { pose.toMatrix(it, 0) } }

            frameStateStore.update(
                ArFrameState(
                    projectionMatrix = projection,
                    viewMatrix = view,
                    anchorMatrix = anchorMatrix
                )
            )

            if (anchorMatrix != null) {
                publishStatus(ArStatus.PLACED)
            } else if (hasTrackedHorizontalPlane(currentSession)) {
                publishStatus(ArStatus.TAP_TO_PLACE)
            } else {
                publishStatus(ArStatus.SEARCHING)
            }
        } catch (error: Exception) {
            onError(error.message ?: "Unable to update ARCore frame")
        }
    }

    private fun handleTap(frame: Frame) {
        val tap = pendingTaps.poll() ?: return
        val hit = frame.hitTest(tap.x, tap.y).firstOrNull { result ->
            val plane = result.trackable as? Plane ?: return@firstOrNull false
            plane.trackingState == TrackingState.TRACKING &&
                plane.isPoseInPolygon(result.hitPose) &&
                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        } ?: return

        val newAnchor = hit.createAnchor()
        anchor?.detach()
        anchor = newAnchor
        publishStatus(ArStatus.PLACED)
    }

    private fun hasTrackedHorizontalPlane(session: Session): Boolean =
        session.getAllTrackables(Plane::class.java).any { plane ->
            plane.trackingState == TrackingState.TRACKING &&
                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }

    private fun updateCameraTexCoords(frame: Frame) {
        quadVertices.position(0)
        cameraTexCoords.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            Coordinates2d.TEXTURE_NORMALIZED,
            cameraTexCoords
        )
    }

    private fun drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(shaderProgram)

        val positionLocation = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
        val texCoordLocation = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
        quadVertices.position(0)
        cameraTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            cameraTexCoords
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(shaderProgram, "s_CameraTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glDepthMask(true)
    }

    private fun publishStatus(status: ArStatus) {
        if (lastStatus == status) return
        lastStatus = status
        onStatusChanged(status)
    }

    private fun publishDiagnostics(diagnostics: ArDiagnostics) {
        if (lastDiagnostics == diagnostics) return
        lastDiagnostics = diagnostics
        Log.i(
            TAG,
            "Camera=${diagnostics.cameraTrackingState}, " +
                "failure=${diagnostics.trackingFailureReason}, " +
                "planes=${diagnostics.totalPlaneCount}, " +
                "tracking=${diagnostics.trackingPlaneCount}, " +
                "horizontal=${diagnostics.horizontalUpwardPlaneCount}, " +
                "usableHorizontal=${diagnostics.trackedHorizontalPlaneCount}"
        )
        onDiagnosticsChanged(diagnostics)
    }

    private fun createExternalTexture(): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        return textureIds[0].also { textureId ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "Camera shader link failed: ${GLES20.glGetProgramInfoLog(program)}"
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            check(compileStatus[0] == GLES20.GL_TRUE) {
                "Camera shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    private data class Tap(val x: Float, val y: Float)

    companion object {
        private const val TAG = "ArCameraRenderer"
        private const val NEAR_CLIP_METERS = 0.05f
        private const val FAR_CLIP_METERS = 100f

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES s_CameraTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(s_CameraTexture, v_TexCoord);
            }
        """

        private fun allocateFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private fun floatBufferOf(vararg values: Float): FloatBuffer = allocateFloatBuffer(values)
    }
}

internal enum class ArStatus {
    SEARCHING,
    TAP_TO_PLACE,
    PLACED
}

internal data class ArDiagnostics(
    val cameraTrackingState: String,
    val trackingFailureReason: String,
    val totalPlaneCount: Int,
    val trackingPlaneCount: Int,
    val horizontalUpwardPlaneCount: Int,
    val trackedHorizontalPlaneCount: Int
)
