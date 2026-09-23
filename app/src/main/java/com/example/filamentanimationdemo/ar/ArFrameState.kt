package com.example.filamentanimationdemo.ar

import java.util.concurrent.atomic.AtomicReference

internal data class ArFrameState(
    val projectionMatrix: FloatArray,
    val viewMatrix: FloatArray,
    val anchorMatrix: FloatArray?
)

internal class ArFrameStateStore {
    private val latestFrame = AtomicReference<ArFrameState?>(null)

    fun update(frameState: ArFrameState) {
        latestFrame.set(frameState)
    }

    fun latest(): ArFrameState? = latestFrame.get()
}
