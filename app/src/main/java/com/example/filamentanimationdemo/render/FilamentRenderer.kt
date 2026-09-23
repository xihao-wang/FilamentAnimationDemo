package com.example.filamentanimationdemo.render

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
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
    private var animationController: AnimationStateController? = null

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
        val animations = (0 until animator.animationCount).associateWith { index ->
            animator.getAnimationName(index).also { name ->
                Log.i(TAG, "Animation[$index] = $name")
            }
        }

        val walkAnimationIndex = findAnimationIndex(
            animations = animations,
            preferredName = WALK_ANIMATION_NAME,
            fallbackName = "Walk"
        ) ?: error("No Walk animation found in $MODEL_PATH")
        val attackAnimationIndex = findAnimationIndex(
            animations = animations,
            preferredName = ATTACK_ANIMATION_NAME,
            fallbackName = "Sword_Attack"
        ) ?: error("No Sword_Attack animation found in $MODEL_PATH")

        animationController = AnimationStateController(
            walkAnimationIndex = walkAnimationIndex,
            walkAnimationDurationSeconds = animator.getAnimationDuration(walkAnimationIndex),
            attackAnimationIndex = attackAnimationIndex,
            attackAnimationDurationSeconds = animator.getAnimationDuration(attackAnimationIndex),
            onStateChanged = { oldState, newState ->
                Log.i(TAG, "Animation state: $oldState -> $newState")
            }
        )
        Log.i(TAG, "Selected Walk animation index=$walkAnimationIndex")
        Log.i(TAG, "Selected Sword_Attack animation index=$attackAnimationIndex")
    }

    private fun findAnimationIndex(
        animations: Map<Int, String>,
        preferredName: String,
        fallbackName: String
    ): Int? = animations.entries.firstOrNull { it.value == preferredName }?.key
        ?: animations.entries.firstOrNull { it.value.equals(fallbackName, ignoreCase = true) }?.key
        ?: animations.entries.firstOrNull { it.value.contains(fallbackName, ignoreCase = true) }?.key

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

    private enum class AnimationState {
        WALK,
        ATTACK
    }

    private data class AnimationFrame(
        val animationIndex: Int,
        val animationTimeSeconds: Float,
        val crossFade: CrossFadeFrame?
    )

    private data class CrossFadeFrame(
        val previousAnimationIndex: Int,
        val previousAnimationTimeSeconds: Float,
        val alpha: Float
    )

    private class AnimationStateController(
        private val walkAnimationIndex: Int,
        private val walkAnimationDurationSeconds: Float,
        private val attackAnimationIndex: Int,
        private val attackAnimationDurationSeconds: Float,
        private val onStateChanged: (AnimationState, AnimationState) -> Unit
    ) {
        var currentState = AnimationState.WALK
            private set
        var stateStartTimeNanos = 0L
            private set
        var currentAnimationElapsedSeconds = 0f
            private set
        private var crossFadeStartTimeNanos = 0L
        private var previousState: AnimationState? = null
        private var previousAnimationTimeSeconds = 0f

        init {
            require(walkAnimationDurationSeconds > 0f) { "Walk animation has no duration" }
            require(attackAnimationDurationSeconds > 0f) { "Sword_Attack animation has no duration" }
        }

        fun animationFrame(frameTimeNanos: Long): AnimationFrame {
            if (stateStartTimeNanos == 0L) {
                stateStartTimeNanos = frameTimeNanos
            }
            currentAnimationElapsedSeconds =
                (frameTimeNanos - stateStartTimeNanos) / NANOS_PER_SECOND

            if (currentState == AnimationState.WALK &&
                currentAnimationElapsedSeconds >= WALK_STATE_DURATION_SECONDS
            ) {
                transitionTo(
                    newState = AnimationState.ATTACK,
                    frameTimeNanos = frameTimeNanos,
                    outgoingAnimationTimeSeconds =
                        currentAnimationElapsedSeconds % walkAnimationDurationSeconds
                )
            }

            val animationIndex: Int
            val animationTimeSeconds: Float
            when (currentState) {
                AnimationState.WALK -> {
                    animationIndex = walkAnimationIndex
                    animationTimeSeconds =
                        currentAnimationElapsedSeconds % walkAnimationDurationSeconds
                }

                AnimationState.ATTACK -> {
                    animationIndex = attackAnimationIndex
                    animationTimeSeconds = currentAnimationElapsedSeconds
                        .coerceAtMost(attackAnimationDurationSeconds)
                }
            }

            return AnimationFrame(
                animationIndex = animationIndex,
                animationTimeSeconds = animationTimeSeconds,
                crossFade = crossFadeFrame(frameTimeNanos)
            )
        }

        fun onFrameRendered(frameTimeNanos: Long) {
            if (currentState == AnimationState.ATTACK &&
                currentAnimationElapsedSeconds >= attackAnimationDurationSeconds
            ) {
                transitionTo(
                    newState = AnimationState.WALK,
                    frameTimeNanos = frameTimeNanos,
                    outgoingAnimationTimeSeconds = attackAnimationDurationSeconds
                )
            }
        }

        private fun crossFadeFrame(frameTimeNanos: Long): CrossFadeFrame? {
            val outgoingState = previousState ?: return null
            val crossFadeElapsedSeconds =
                (frameTimeNanos - crossFadeStartTimeNanos) / NANOS_PER_SECOND
            if (crossFadeElapsedSeconds >= CROSS_FADE_DURATION_SECONDS) {
                previousState = null
                return null
            }

            val outgoingAnimationTime = when (outgoingState) {
                AnimationState.WALK ->
                    (previousAnimationTimeSeconds + crossFadeElapsedSeconds) %
                        walkAnimationDurationSeconds
                AnimationState.ATTACK ->
                    (previousAnimationTimeSeconds + crossFadeElapsedSeconds)
                        .coerceAtMost(attackAnimationDurationSeconds)
            }
            val outgoingAnimationIndex = when (outgoingState) {
                AnimationState.WALK -> walkAnimationIndex
                AnimationState.ATTACK -> attackAnimationIndex
            }

            return CrossFadeFrame(
                previousAnimationIndex = outgoingAnimationIndex,
                previousAnimationTimeSeconds = outgoingAnimationTime,
                alpha = (crossFadeElapsedSeconds / CROSS_FADE_DURATION_SECONDS)
                    .coerceIn(0f, 1f)
            )
        }

        private fun transitionTo(
            newState: AnimationState,
            frameTimeNanos: Long,
            outgoingAnimationTimeSeconds: Float
        ) {
            val oldState = currentState
            previousState = oldState
            previousAnimationTimeSeconds = outgoingAnimationTimeSeconds
            crossFadeStartTimeNanos = frameTimeNanos
            currentState = newState
            stateStartTimeNanos = frameTimeNanos
            currentAnimationElapsedSeconds = 0f
            onStateChanged(oldState, newState)
        }
    }

    private companion object {
        const val TAG = "FilamentRenderer"
        const val MODEL_PATH = "models/warrior.glb"
        const val WALK_ANIMATION_NAME = "CharacterArmature|Walk"
        const val ATTACK_ANIMATION_NAME = "CharacterArmature|Sword_Attack"
        const val WALK_STATE_DURATION_SECONDS = 3f
        const val CROSS_FADE_DURATION_SECONDS = 0.25f
        const val NANOS_PER_SECOND = 1_000_000_000f

        // Initializes Filament, gltfio, and the utility native libraries once per process.
        init {
            Utils.init()
        }
    }
}
