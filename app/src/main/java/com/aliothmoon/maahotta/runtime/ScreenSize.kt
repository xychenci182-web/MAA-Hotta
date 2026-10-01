package com.aliothmoon.maahotta.runtime

import android.graphics.Point
import android.view.Display
import android.view.WindowManager
import com.aliothmoon.maahotta.HottaApp

fun realScreenSize(): Point {
    val wm = HottaApp.instance.getSystemService(WindowManager::class.java)
    val p = Point()
    // Window metrics belong to our portrait activity while the game is landscape.
    // The default display follows the current rotation, including on other phones.
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealSize(p)
    return p
}
