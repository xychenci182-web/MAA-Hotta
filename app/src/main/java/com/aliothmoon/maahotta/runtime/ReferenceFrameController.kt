package com.aliothmoon.maahotta.runtime

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import kotlin.math.roundToInt

/** Gives recognition a 720-pixel-high frame and maps actions back to raw touch space. */
class ReferenceFrameController(private val source: DeviceController) : DeviceController by source {
    companion object {
        const val REFERENCE_HEIGHT = 720
    }

    override fun screenSize(): Point {
        val raw = source.screenSize()
        if (raw.x <= 0 || raw.y <= 0) return Point(0, 0)
        return Point((raw.x.toDouble() * REFERENCE_HEIGHT / raw.y).roundToInt(), REFERENCE_HEIGHT)
    }

    override suspend fun screenshot(): Bitmap? {
        val raw = source.screenshot() ?: return null
        if (raw.height == REFERENCE_HEIGHT) return raw
        return try {
            val width = (raw.width.toDouble() * REFERENCE_HEIGHT / raw.height).roundToInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, width, REFERENCE_HEIGHT, true)
        } finally {
            raw.recycle()
        }
    }

    override suspend fun tap(x: Int, y: Int, holdMs: Long) {
        val raw = source.screenSize()
        val reference = screenSize()
        if (reference.x <= 0 || reference.y <= 0) return
        source.tap(
            (x.toDouble() * raw.x / reference.x).roundToInt().coerceIn(0, raw.x - 1),
            (y.toDouble() * raw.y / reference.y).roundToInt().coerceIn(0, raw.y - 1),
            holdMs,
        )
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val raw = source.screenSize()
        val reference = screenSize()
        if (reference.x <= 0 || reference.y <= 0) return
        fun rawX(x: Int): Int =
            (x.toDouble() * raw.x / reference.x).roundToInt().coerceIn(0, raw.x - 1)
        fun rawY(y: Int): Int =
            (y.toDouble() * raw.y / reference.y).roundToInt().coerceIn(0, raw.y - 1)
        source.swipe(rawX(x1), rawY(y1), rawX(x2), rawY(y2), durationMs)
    }

    override suspend fun viewInfo(viewIdSuffix: String): ViewInfo? {
        val info = source.viewInfo(viewIdSuffix) ?: return null
        val raw = source.screenSize()
        val reference = screenSize()
        if (raw.x <= 0 || raw.y <= 0) return info
        fun refX(x: Int): Int = (x.toDouble() * reference.x / raw.x).roundToInt()
        fun refY(y: Int): Int = (y.toDouble() * reference.y / raw.y).roundToInt()
        val bounds = info.bounds
        return info.copy(bounds = Rect(
            refX(bounds.left), refY(bounds.top), refX(bounds.right), refY(bounds.bottom),
        ))
    }
}
