package com.example.filamentanimationdemo.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.filamentanimationdemo.MainActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException

class ArActivity : ComponentActivity() {

    private lateinit var cameraView: GLSurfaceView
    private lateinit var cameraRenderer: ArCameraRenderer
    private lateinit var filamentRenderer: ArFilamentRenderer
    private lateinit var instructionView: TextView
    private var session: Session? = null
    private var installRequested = false
    private var isResumed = false
    private var isReturningToViewer = false
    private var currentStatus = ArStatus.SEARCHING
    private var currentDiagnostics: ArDiagnostics? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (isResumed) resumeAr()
        } else {
            returnToViewer("Camera permission is required for AR mode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frameStateStore = ArFrameStateStore()
        cameraView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }
        val filamentView = TextureView(this).apply {
            isOpaque = false
        }
        instructionView = TextView(this).apply {
            text = STATUS_SEARCHING
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(32, 20, 32, 20)
        }

        cameraRenderer = ArCameraRenderer(
            cameraView = cameraView,
            frameStateStore = frameStateStore,
            onStatusChanged = { status ->
                runOnUiThread {
                    currentStatus = status
                    updateInstructionText()
                }
            },
            onDiagnosticsChanged = { diagnostics ->
                runOnUiThread {
                    currentDiagnostics = diagnostics
                    updateInstructionText()
                }
            },
            onError = { message -> runOnUiThread { returnToViewer(message) } }
        )
        cameraView.setRenderer(cameraRenderer)
        cameraView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        filamentRenderer = ArFilamentRenderer(
            context = this,
            textureView = filamentView,
            frameStateStore = frameStateStore,
            onError = { message -> runOnUiThread { returnToViewer(message) } }
        )
        filamentView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                cameraRenderer.queueTap(event.x, event.y)
            }
            true
        }

        setContentView(
            FrameLayout(this).apply {
                addView(
                    cameraView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    filamentView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    instructionView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    ).apply { topMargin = 48 }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        resumeAr()
    }

    override fun onPause() {
        isResumed = false
        filamentRenderer.stop()
        cameraView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        cameraRenderer.releaseAnchor()
        session?.close()
        session = null
        // ModelViewer is released by its TextureView detach listener.
        filamentRenderer.release()
        super.onDestroy()
    }

    private fun resumeAr() {
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                session = Session(this).apply {
                    configure(
                        config.apply {
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                            focusMode = Config.FocusMode.AUTO
                            lightEstimationMode = Config.LightEstimationMode.DISABLED
                        }
                    )
                    Log.i(
                        TAG,
                        "ARCore configured: planeFindingMode=${config.planeFindingMode}, " +
                            "focusMode=${config.focusMode}"
                    )
                }
                cameraRenderer.session = session
            }

            session?.resume()
            cameraView.onResume()
            filamentRenderer.start()
        } catch (error: CameraNotAvailableException) {
            returnToViewer("AR camera is not available. Returning to the 3D viewer.")
        } catch (error: Exception) {
            returnToViewer(
                error.message ?: "ARCore is not available. Returning to the 3D viewer."
            )
        }
    }

    private fun returnToViewer(message: String) {
        if (isReturningToViewer) return
        isReturningToViewer = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun updateInstructionText() {
        val diagnostics = currentDiagnostics
        instructionView.text = if (diagnostics == null) {
            currentStatus.message
        } else {
            "${currentStatus.message}\n" +
                "Camera: ${diagnostics.cameraTrackingState}\n" +
                "Failure: ${diagnostics.trackingFailureReason}\n" +
                "Planes: ${diagnostics.totalPlaneCount}\n" +
                "Tracking: ${diagnostics.trackingPlaneCount}\n" +
                "Horizontal: ${diagnostics.horizontalUpwardPlaneCount}\n" +
                "Usable: ${diagnostics.trackedHorizontalPlaneCount}"
        }
    }

    private val ArStatus.message: String
        get() = when (this) {
            ArStatus.SEARCHING -> STATUS_SEARCHING
            ArStatus.TAP_TO_PLACE -> "检测到平面，点击放置 Warrior"
            ArStatus.PLACED -> "Warrior 已放置"
        }

    private companion object {
        const val TAG = "ArActivity"
        const val STATUS_SEARCHING = "移动手机以检测桌面或地面"
    }
}
