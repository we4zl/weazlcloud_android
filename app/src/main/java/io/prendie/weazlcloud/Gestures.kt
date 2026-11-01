package io.prendie.weazlcloud

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/** Leave one-finger scrolling/paging to the parent until the image is zoomed. */
suspend fun PointerInputScope.mediaGestures(
    isZoomed: () -> Boolean = { false },
    transform: (Offset, Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2 || isZoomed()) {
                transform(event.calculatePan(), event.calculateZoom())
                event.changes.forEach { if (it.pressed) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
