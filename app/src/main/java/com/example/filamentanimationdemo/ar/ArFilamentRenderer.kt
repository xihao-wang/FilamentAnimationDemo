package com.example.filamentanimationdemo.ar

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import com.example.filamentanimationdemo.animation.CharacterAnimationController
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/** Transparent Filament layer that places and animates the Warrior at the latest AR Anchor. */
internal class ArFilamentRenderer(
    context: Context,
    textureView: TextureView,
    private val frameStateStore: ArFrameStateStore,
    private val onError: (String) -> Unit
) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private val modelViewer: ModelViewer
    private var animationController: CharacterAnimationController? = null
    private var isRunning = false
    private var modelIsInScene = true
    private var modelScale = DEFAULT_MODEL_SCALE
    private val modelOffsetMatrix = FloatArray(16)

    init {
        Utils.init()
        textureView.isOpaque = false
        modelViewer = ModelViewer(textureView)
        modelViewer.autoPlayAnimations = false
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
            clear = true
            discard = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
        configureLighting()
        loadModel(context)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        choreographer.removeFrameCallback(this)
    }

    fun release() {
        stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        val frameState = frameStateStore.latest()
        if (frameState != null) {
            updateArCamera(frameState)
            updateAnchorTransform(frameState.anchorMatrix)
        }

        if (modelIsInScene) {
            updateAnimation(frameTimeNanos)
        }
        modelViewer.render(frameTimeNanos)
        if (modelIsInScene) {
            animationController?.onFrameRendered(frameTimeNanos)
        }
        choreographer.postFrameCallback(this)
    }

    private fun loadModel(context: Context) {
        runCatching {
            val modelBuffer = context.assets.open(MODEL_PATH).use { input ->
                ByteBuffer.wrap(input.readBytes())
            }
            modelViewer.loadModelGlb(modelBuffer)
            val asset = checkNotNull(modelViewer.asset) { "Filament could not parse $MODEL_PATH" }
            val bounds = asset.boundingBox
            val modelHeight = bounds.halfExtent[1] * 2f
            modelScale = TARGET_CHARACTER_HEIGHT_METERS / modelHeight

            Matrix.setIdentityM(modelOffsetMatrix, 0)
            Matrix.translateM(
                modelOffsetMatrix,
                0,
                -bounds.center[0],
                -(bounds.center[1] - bounds.halfExtent[1]),
                -bounds.center[2]
            )

            val animator = checkNotNull(modelViewer.animator) { "Model does not contain an animator" }
            animationController = CharacterAnimationController.create(
                animator = animator,
                onAnimationFound = { index, name -> Log.i(TAG, "Animation[$index] = $name") },
                onStateChanged = { oldState, newState ->
                    Log.i(TAG, "Animation state: $oldState -> $newState")
                }
            )
            removeModelFromScene()
        }.onFailure { error ->
            onError(error.message ?: "Unable to load $MODEL_PATH")
        }
    }

    private fun configureLighting() {
        modelViewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(30_000f)
            .build(modelViewer.engine)

        val lightManager = modelViewer.engine.lightManager
        val lightInstance = lightManager.getInstance(modelViewer.light)
        lightManager.setDirection(lightInstance, 0.3f, -0.8f, -0.6f)
        lightManager.setIntensity(lightInstance, 65_000f)
    }

    private fun updateArCamera(frameState: ArFrameState) {
        val projection = DoubleArray(16) { index -> frameState.projectionMatrix[index].toDouble() }
        modelViewer.camera.setCustomProjection(projection, NEAR_CLIP_METERS, FAR_CLIP_METERS)

        val cameraModelMatrix = FloatArray(16)
        if (Matrix.invertM(cameraModelMatrix, 0, frameState.viewMatrix, 0)) {
            modelViewer.camera.setModelMatrix(cameraModelMatrix)
        }
    }

    private fun updateAnchorTransform(anchorMatrix: FloatArray?) {
        if (anchorMatrix == null) {
            removeModelFromScene()
            return
        }
        addModelToScene()

        val scaleMatrix = FloatArray(16)
        val scaledOffsetMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, modelScale, modelScale, modelScale)
        Matrix.multiplyMM(scaledOffsetMatrix, 0, scaleMatrix, 0, modelOffsetMatrix, 0)
        Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, scaledOffsetMatrix, 0)

        val asset = modelViewer.asset ?: return
        val transformManager = modelViewer.engine.transformManager
        val rootInstance = transformManager.getInstance(asset.root)
        transformManager.setTransform(rootInstance, modelMatrix)
    }

    private fun updateAnimation(frameTimeNanos: Long) {
        val controller = animationController ?: return
        val animator = modelViewer.animator ?: return
        val animationFrame = controller.animationFrame(frameTimeNanos)
        animator.applyAnimation(animationFrame.animationIndex, animationFrame.animationTimeSeconds)
        animationFrame.crossFade?.let { crossFade ->
            animator.applyCrossFade(
                crossFade.previousAnimationIndex,
                crossFade.previousAnimationTimeSeconds,
                crossFade.alpha
            )
        }
        animator.updateBoneMatrices()
    }

    private fun addModelToScene() {
        if (modelIsInScene) return
        modelViewer.asset?.let { modelViewer.scene.addEntities(it.entities) }
        modelIsInScene = true
    }

    private fun removeModelFromScene() {
        if (!modelIsInScene) return
        modelViewer.asset?.let { modelViewer.scene.removeEntities(it.entities) }
        modelIsInScene = false
    }

    private companion object {
        const val TAG = "ArFilamentRenderer"
        const val MODEL_PATH = "models/warrior.glb"
        const val TARGET_CHARACTER_HEIGHT_METERS = 0.5f
        const val DEFAULT_MODEL_SCALE = 0.17f
        const val NEAR_CLIP_METERS = 0.05
        const val FAR_CLIP_METERS = 100.0
    }
}
