package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Reads the character name shown near the top of the in-game Settings page. */
object CharacterNameReader {
    suspend fun read(screen: Bitmap): String? {
        // Start immediately before the name. The previous wider crop included
        // the gender/avatar decoration, which OCR could turn into letters such
        // as "md" and prepend to a Chinese character name.
        val left = (screen.width * 0.34f).roundToInt().coerceIn(0, screen.width - 1)
        val top = (screen.height * 0.105f).roundToInt().coerceIn(0, screen.height - 1)
        val right = (screen.width * 0.565f).roundToInt().coerceIn(left + 1, screen.width)
        val bottom = (screen.height * 0.205f).roundToInt().coerceIn(top + 1, screen.height)
        val crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        if (scaled !== crop) crop.recycle()

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result = recognizer.process(InputImage.fromBitmap(scaled, 0)).awaitResult()
            result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    var text = line.text
                        .replace(Regex("\\s+"), "")
                        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
                        .trim()
                    if (text.length > 2 && text.take(2).equals("md", ignoreCase = true) &&
                        text[2].code in 0x3400..0x9FFF
                    ) {
                        text = text.drop(2)
                    }
                    val letterOrDigitCount = text.count(Char::isLetterOrDigit)
                    if (text.length !in 2..20 || letterOrDigitCount < 2 ||
                        text.contains("UID", ignoreCase = true) ||
                        text.contains("服务器") || text.contains("用户中心")
                    ) {
                        null
                    } else {
                        val chineseCount = text.count { it.code in 0x3400..0x9FFF }
                        val height = line.boundingBox?.height() ?: 0
                        Triple(text, chineseCount, height)
                    }
                }
                .maxByOrNull { (_, chineseCount, height) -> chineseCount * 1_000 + height }
                ?.first
        } finally {
            recognizer.close()
            scaled.recycle()
        }
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
