package com.aliothmoon.maahotta.engine

import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.PageObservation

/** Recognition and diagnostic output describe the same frame captured before recovery. */
internal data class TaskFailureSnapshot(
    val screen: Bitmap?,
    val page: PageObservation,
    val screenshotPath: String?,
    val reasonPath: String?,
    val captureError: String? = null,
) {
    fun recycle() { screen?.recycle() }
}
