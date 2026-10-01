package com.aliothmoon.maahotta.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.ByteArrayOutputStream

class ShellUserService : IShellService.Stub {
    constructor() : super()

    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) (stdout + stderr).trim()
            else "ERR:$exitCode ${(stdout + stderr).trim()}"
        }.getOrElse {
            // Commands can contain passwords; never write their contents to logs.
            Timber.e(it, "shell command failed")
            "ERR:${it.message}"
        }
    }

    override fun screenshotJpeg(maxWidth: Int, quality: Int): ByteArray {
        return runCatching {
            // Read a fresh capture directly. A fixed temporary file could return an
            // old frame after screencap failed, which makes page checks unsafe.
            val process = ProcessBuilder("screencap", "-p")
                .redirectErrorStream(true)
                .start()
            val png = process.inputStream.use { it.readBytes() }
            if (process.waitFor() != 0 || png.isEmpty()) return ByteArray(0)
            val raw = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return ByteArray(0)
            try {
                val scaled = if (maxWidth > 0 && raw.width > maxWidth) {
                    val height = (raw.height * maxWidth.toFloat() / raw.width)
                        .toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(raw, maxWidth, height, true)
                } else {
                    raw
                }
                try {
                    val out = ByteArrayOutputStream()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), out)) {
                        return ByteArray(0)
                    }
                    out.toByteArray()
                } finally {
                    if (scaled !== raw) scaled.recycle()
                }
            } finally {
                raw.recycle()
            }
        }.getOrElse {
            Timber.w(it, "screencap failed")
            ByteArray(0)
        }
    }

    companion object {
        @Suppress("unused")
        fun describeContentsForHidden(): Int = ParcelFileDescriptor.MODE_READ_ONLY
    }
}
