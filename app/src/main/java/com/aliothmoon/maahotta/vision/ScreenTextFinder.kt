package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

data class ScreenTextMatch(
    val target: String,
    val point: Point,
)

/** Locates Chinese UI labels and returns their current on-screen center. */
object ScreenTextFinder {
    private data class TextCandidate(val text: String, val box: Rect)

    suspend fun find(screen: Bitmap, targets: List<String>): ScreenTextMatch? =
        findAll(screen, targets).firstOrNull()

    suspend fun findAll(screen: Bitmap, targets: List<String>): List<ScreenTextMatch> {
        val candidates = recognize(screen)
        return match(candidates, targets, allowOneEdit = false)
    }

    /** Retry small game labels in a bounded area at double size, allowing one OCR typo. */
    suspend fun findAllInRegion(
        screen: Bitmap,
        targets: List<String>,
        region: SearchRegion,
        onRecognized: (List<String>) -> Unit = {},
    ): List<ScreenTextMatch> {
        val left = (screen.width * region.left).roundToInt().coerceIn(0, screen.width - 1)
        val top = (screen.height * region.top).roundToInt().coerceIn(0, screen.height - 1)
        val right = (screen.width * region.right).roundToInt().coerceIn(left + 1, screen.width)
        val bottom = (screen.height * region.bottom).roundToInt().coerceIn(top + 1, screen.height)
        val crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        val enlarged = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        return try {
            val candidates = recognize(enlarged)
            onRecognized(candidates.map { it.text })
            match(candidates, targets, allowOneEdit = true).map { found ->
                found.copy(point = Point(left + found.point.x / 2, top + found.point.y / 2))
            }
        } finally {
            enlarged.recycle()
            crop.recycle()
        }
    }

    private suspend fun recognize(screen: Bitmap): List<TextCandidate> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result = recognizer.process(InputImage.fromBitmap(screen, 0)).awaitResult()
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { TextCandidate(line.text, it) }
            }
            val candidates = lines.toMutableList()
            // Some game labels arrive as two or three neighboring OCR lines.
            for (line in lines) {
                var merged = line
                val used = mutableSetOf(line)
                repeat(2) {
                    val next = lines.filter { candidate ->
                        candidate !in used && candidate.box.left >= merged.box.right &&
                            candidate.box.left - merged.box.right <=
                            maxOf(merged.box.height(), candidate.box.height()) * 3 / 2 &&
                            minOf(merged.box.bottom, candidate.box.bottom) -
                            maxOf(merged.box.top, candidate.box.top) >=
                            minOf(merged.box.height(), candidate.box.height()) / 2
                    }.minByOrNull { it.box.left } ?: return@repeat
                    used += next
                    merged = TextCandidate(
                        merged.text + next.text,
                        Rect(merged.box).apply { union(next.box) },
                    )
                    candidates += merged
                }
            }
            candidates
        } finally {
            recognizer.close()
        }
    }

    private fun match(
        candidates: List<TextCandidate>,
        targets: List<String>,
        allowOneEdit: Boolean,
    ): List<ScreenTextMatch> {
        val exact = targets.flatMap { target ->
            val expected = normalize(target)
            candidates.mapNotNull { candidate ->
                if (normalize(candidate.text).contains(expected)) {
                    ScreenTextMatch(target, Point(candidate.box.centerX(), candidate.box.centerY()))
                } else null
            }
        }
        if (exact.isNotEmpty() || !allowOneEdit) return exact
        return targets.flatMap { target ->
            val expected = normalize(target)
            if (expected.length < 4) return@flatMap emptyList()
            candidates.mapNotNull { candidate ->
                if (containsWithinOneEdit(normalize(candidate.text), expected)) {
                    ScreenTextMatch(target, Point(candidate.box.centerX(), candidate.box.centerY()))
                } else null
            }
        }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit)
            .lowercase()

    private fun containsWithinOneEdit(text: String, target: String): Boolean {
        if (text.isEmpty()) return false
        for (length in (target.length - 1)..(target.length + 1)) {
            if (length <= 0 || length > text.length) continue
            for (start in 0..text.length - length) {
                if (withinOneEdit(text.substring(start, start + length), target)) return true
            }
        }
        return false
    }

    private fun withinOneEdit(actual: String, expected: String): Boolean {
        if (abs(actual.length - expected.length) > 1) return false
        var actualIndex = 0
        var expectedIndex = 0
        var edits = 0
        while (actualIndex < actual.length && expectedIndex < expected.length) {
            if (actual[actualIndex] == expected[expectedIndex]) {
                actualIndex++
                expectedIndex++
                continue
            }
            if (++edits > 1) return false
            when {
                actual.length > expected.length -> actualIndex++
                actual.length < expected.length -> expectedIndex++
                else -> {
                    actualIndex++
                    expectedIndex++
                }
            }
        }
        return edits + (actual.length - actualIndex) + (expected.length - expectedIndex) <= 1
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }
}
