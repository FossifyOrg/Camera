package org.fossify.camera.models

import androidx.annotation.StringRes
import org.fossify.camera.R

enum class CaptureMode(@StringRes val stringResId: Int) {
    MINIMIZE_LATENCY(R.string.minimize_latency),
    MAXIMIZE_QUALITY(R.string.maximize_quality)
}
