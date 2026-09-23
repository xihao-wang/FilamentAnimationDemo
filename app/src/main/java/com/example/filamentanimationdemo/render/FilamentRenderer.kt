package com.example.filamentanimationdemo.render

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.example.filamentanimationdemo.animation.CharacterAnimationController
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Small owner for Filament's model viewer and render loop.
 *
 * ModelViewer owns the Filament Engine and releases it when the SurfaceView is detached.
 */
class FilamentRenderer(
    context: Context,
    surfaceView: SurfaceView,
    private val onError: (String) -> Unit
) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private val modelViewer = ModelViewer(surfaceView)
    private var isRunning = false
    private var animationController: CharacterAnimationController? = null

    init {
        modelViewer.autoPlayAnimations = false
        configureClearColor()
        configureLighting()
        surfaceView.setOnTouchListener(modelViewer)
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

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        updateAnimation(frameTimeNanos)
        modelViewer.render(frameTimeNanos)
        animationController?.onFrameRendered(frameTimeNanos)
        choreographer.postFrameCallback(this)
    }

    private fun loadModel(context: Context) {
        runCatching {
            val modelBuffer = context.assets.open(MODEL_PATH).use { input ->
                ByteBuffer.wrap(input.readBytes())
            }

            modelViewer.loadModelGlb(modelBuffer)
            checkNotNull(modelViewer.asset) { "Filament could not parse $MODEL_PATH" }
            frameModel()
            configureAnimationController()
        }.onFailure { error ->
            onError(error.message ?: "Unable to load $MODEL_PATH")
        }
    }

    /** Fits the model's bounding box into ModelViewer's default orbit-camera framing. */
    private fun frameModel() {
        val boundingBox = checkNotNull(modelViewer.asset).boundingBox
        Log.i(
            TAG,
            "Model bounding box center=${boundingBox.center.contentToString()}, " +
                "halfExtent=${boundingBox.halfExtent.contentToString()}"
        )
        modelViewer.transformToUnitCube()
    }

    /** Clears every frame to opaque white so animated geometry never accumulates in the color buffer. */
    private fun configureClearColor() {
        modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
            clear = true
            discard = true
            clearColor = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        }
    }

    /** Combines neutral environment light with lower-intensity key, fill, and rim lights. */
    private fun configureLighting() {
        val environmentLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.0f))
            .intensity(30_000f)
            .build(modelViewer.engine)
        modelViewer.scene.indirectLight = environmentLight

        val lightManager = modelViewer.engine.lightManager
        val lightInstance = lightManager.getInstance(modelViewer.light)
        lightManager.setDirection(lightInstance, 0.3f, -0.7f, -1.0f)
        lightManager.setIntensity(lightInstance, 80_000f)

        addDirectionalLight(
            direction = floatArrayOf(-0.8f, -0.5f, 0.35f),
            color = floatArrayOf(0.85f, 0.9f, 1.0f),
            intensity = 18_000f
        )
        addDirectionalLight(
            direction = floatArrayOf(0.9f, -0.35f, 0.45f),
            color = floatArrayOf(1.0f, 0.9f, 0.8f),
            intensity = 25_000f
        )
    }

    private fun addDirectionalLight(
        direction: FloatArray,
        color: FloatArray,
        intensity: Float
    ) {
        val lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(direction[0], direction[1], direction[2])
            .color(color[0], color[1], color[2])
            .intensity(intensity)
            .castShadows(false)
            .build(modelViewer.engine, lightEntity)
        modelViewer.scene.addEntity(lightEntity)
    }

    private fun configureAnimationController() {
        val animator = checkNotNull(modelViewer.animator) { "Model does not contain an animator" }
        animationController = CharacterAnimationController.create(
            animator = animator,
            onAnimationFound = { index, name ->
                Log.i(TAG, "Animation[$index] = $name")
            },
            onStateChanged = { oldState, newState ->
                Log.i(TAG, "Animation state: $oldState -> $newState")
            }
        )
        Log.i(TAG, "Selected Walk animation index=${animationController?.walkAnimationIndex}")
        Log.i(TAG, "Selected Sword_Attack animation index=${animationController?.attackAnimationIndex}")
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

    private companion object {
        const val TAG = "FilamentRenderer"
        const val MODEL_PATH = "models/warrior.glb"

        // Initializes Filament, gltfio, and the utility native libraries once per process.
        init {
            Utils.init()
        }
    }
}
