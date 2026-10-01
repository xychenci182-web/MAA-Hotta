package com.aliothmoon.maahotta.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

class TemplateStore(private val context: Context) {
    private val cache = mutableMapOf<String, Bitmap>()

    fun get(name: String): Bitmap? {
        cache[name]?.let { return it }
        val captured = File(context.getExternalFilesDir("templates"), "$name.png")
        val bmp = when {
            captured.exists() -> BitmapFactory.decodeFile(captured.absolutePath)
            else -> runCatching {
                context.assets.open("templates/$name.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bmp != null) cache[name] = bmp
        return bmp
    }

    fun has(name: String): Boolean = get(name) != null

    fun saveCapture(name: String, bitmap: Bitmap): File {
        val dir = context.getExternalFilesDir("templates")!!
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.remove(name)
        return file
    }
}
