package com.aggapp.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One position is shared by pointer input, settling and drawing, including the release frame. */
internal class LiquidNavigationMotion(private val scope: CoroutineScope, initialPosition: Float) {
    var position by mutableFloatStateOf(initialPosition)
        private set
    var translation by mutableFloatStateOf(0f)
        private set
    var requestedVelocity by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var settling by mutableStateOf(false)
        private set
    private var target = initialPosition
    private var positionJob: Job? = null
    private var translationJob: Job? = null
    private var generation = 0

    fun beginDrag(position: Float, translation: Float) {
        generation++
        positionJob?.cancel()
        translationJob?.cancel()
        settling = false
        dragging = true
        this.position = position
        this.translation = translation
    }

    fun dragBy(delta: Float, slotWidth: Float, lastIndex: Int, velocity: Float) {
        position = (position + delta / slotWidth).coerceIn(0f, lastIndex.toFloat())
        translation += delta
        requestedVelocity = velocity
    }

    fun release(destination: Float) {
        dragging = false
        requestedVelocity = 0f
        settleTo(destination)
        returnPanel()
    }

    fun followSelection(destination: Float) {
        if (!dragging && target == destination) return
        release(destination)
    }

    fun cancel(destination: Float) = release(destination)

    private fun settleTo(destination: Float) {
        target = destination
        val ticket = ++generation
        positionJob?.cancel()
        val start = position
        settling = abs(start - destination) >= 0.025f
        positionJob = scope.launch {
            try {
                animate(start, destination, animationSpec = spring(1f, 1000f, 0.001f)) { value, _ ->
                    position = value
                    settling = abs(value - destination) >= 0.025f
                }
            } finally {
                if (generation == ticket) settling = false
            }
        }
    }

    private fun returnPanel() {
        translationJob?.cancel()
        val start = translation
        translationJob = scope.launch {
            animate(start, 0f, animationSpec = spring(1f, 300f, 0.5f)) { value, _ ->
                translation = value
            }
        }
    }
}
