package com.example.filamentanimationdemo.animation

import com.google.android.filament.gltfio.Animator

internal enum class CharacterAnimationState {
    WALK,
    ATTACK
}

internal data class CharacterAnimationFrame(
    val animationIndex: Int,
    val animationTimeSeconds: Float,
    val crossFade: CharacterCrossFadeFrame?
)

internal data class CharacterCrossFadeFrame(
    val previousAnimationIndex: Int,
    val previousAnimationTimeSeconds: Float,
    val alpha: Float
)

/** Shared Walk -> Sword_Attack state machine used by both the normal and AR renderers. */
internal class CharacterAnimationController private constructor(
    val walkAnimationIndex: Int,
    private val walkAnimationDurationSeconds: Float,
    val attackAnimationIndex: Int,
    private val attackAnimationDurationSeconds: Float,
    private val onStateChanged: (CharacterAnimationState, CharacterAnimationState) -> Unit
) {
    var currentState = CharacterAnimationState.WALK
        private set
    var stateStartTimeNanos = 0L
        private set
    var currentAnimationElapsedSeconds = 0f
        private set
    private var crossFadeStartTimeNanos = 0L
    private var previousState: CharacterAnimationState? = null
    private var previousAnimationTimeSeconds = 0f

    init {
        require(walkAnimationDurationSeconds > 0f) { "Walk animation has no duration" }
        require(attackAnimationDurationSeconds > 0f) { "Sword_Attack animation has no duration" }
    }

    fun animationFrame(frameTimeNanos: Long): CharacterAnimationFrame {
        if (stateStartTimeNanos == 0L) {
            stateStartTimeNanos = frameTimeNanos
        }
        currentAnimationElapsedSeconds =
            (frameTimeNanos - stateStartTimeNanos) / NANOS_PER_SECOND

        if (currentState == CharacterAnimationState.WALK &&
            currentAnimationElapsedSeconds >= WALK_STATE_DURATION_SECONDS
        ) {
            transitionTo(
                newState = CharacterAnimationState.ATTACK,
                frameTimeNanos = frameTimeNanos,
                outgoingAnimationTimeSeconds =
                    currentAnimationElapsedSeconds % walkAnimationDurationSeconds
            )
        }

        val animationIndex: Int
        val animationTimeSeconds: Float
        when (currentState) {
            CharacterAnimationState.WALK -> {
                animationIndex = walkAnimationIndex
                animationTimeSeconds =
                    currentAnimationElapsedSeconds % walkAnimationDurationSeconds
            }

            CharacterAnimationState.ATTACK -> {
                animationIndex = attackAnimationIndex
                animationTimeSeconds = currentAnimationElapsedSeconds
                    .coerceAtMost(attackAnimationDurationSeconds)
            }
        }

        return CharacterAnimationFrame(
            animationIndex = animationIndex,
            animationTimeSeconds = animationTimeSeconds,
            crossFade = crossFadeFrame(frameTimeNanos)
        )
    }

    fun onFrameRendered(frameTimeNanos: Long) {
        if (currentState == CharacterAnimationState.ATTACK &&
            currentAnimationElapsedSeconds >= attackAnimationDurationSeconds
        ) {
            transitionTo(
                newState = CharacterAnimationState.WALK,
                frameTimeNanos = frameTimeNanos,
                outgoingAnimationTimeSeconds = attackAnimationDurationSeconds
            )
        }
    }

    private fun crossFadeFrame(frameTimeNanos: Long): CharacterCrossFadeFrame? {
        val outgoingState = previousState ?: return null
        val crossFadeElapsedSeconds =
            (frameTimeNanos - crossFadeStartTimeNanos) / NANOS_PER_SECOND
        if (crossFadeElapsedSeconds >= CROSS_FADE_DURATION_SECONDS) {
            previousState = null
            return null
        }

        val outgoingAnimationTime = when (outgoingState) {
            CharacterAnimationState.WALK ->
                (previousAnimationTimeSeconds + crossFadeElapsedSeconds) %
                    walkAnimationDurationSeconds
            CharacterAnimationState.ATTACK ->
                (previousAnimationTimeSeconds + crossFadeElapsedSeconds)
                    .coerceAtMost(attackAnimationDurationSeconds)
        }
        val outgoingAnimationIndex = when (outgoingState) {
            CharacterAnimationState.WALK -> walkAnimationIndex
            CharacterAnimationState.ATTACK -> attackAnimationIndex
        }

        return CharacterCrossFadeFrame(
            previousAnimationIndex = outgoingAnimationIndex,
            previousAnimationTimeSeconds = outgoingAnimationTime,
            alpha = (crossFadeElapsedSeconds / CROSS_FADE_DURATION_SECONDS)
                .coerceIn(0f, 1f)
        )
    }

    private fun transitionTo(
        newState: CharacterAnimationState,
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

    companion object {
        fun create(
            animator: Animator,
            onAnimationFound: (Int, String) -> Unit = { _, _ -> },
            onStateChanged: (CharacterAnimationState, CharacterAnimationState) -> Unit = { _, _ -> }
        ): CharacterAnimationController {
            val animations = (0 until animator.animationCount).associateWith { index ->
                animator.getAnimationName(index).also { name -> onAnimationFound(index, name) }
            }
            val walkIndex = findAnimationIndex(
                animations,
                preferredName = "CharacterArmature|Walk",
                fallbackName = "Walk"
            ) ?: error("No Walk animation found")
            val attackIndex = findAnimationIndex(
                animations,
                preferredName = "CharacterArmature|Sword_Attack",
                fallbackName = "Sword_Attack"
            ) ?: error("No Sword_Attack animation found")

            return CharacterAnimationController(
                walkAnimationIndex = walkIndex,
                walkAnimationDurationSeconds = animator.getAnimationDuration(walkIndex),
                attackAnimationIndex = attackIndex,
                attackAnimationDurationSeconds = animator.getAnimationDuration(attackIndex),
                onStateChanged = onStateChanged
            )
        }

        private fun findAnimationIndex(
            animations: Map<Int, String>,
            preferredName: String,
            fallbackName: String
        ): Int? = animations.entries.firstOrNull { it.value == preferredName }?.key
            ?: animations.entries.firstOrNull {
                it.value.equals(fallbackName, ignoreCase = true)
            }?.key
            ?: animations.entries.firstOrNull {
                it.value.contains(fallbackName, ignoreCase = true)
            }?.key

        private const val WALK_STATE_DURATION_SECONDS = 3f
        private const val CROSS_FADE_DURATION_SECONDS = 0.25f
        private const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
