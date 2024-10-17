package org.fossify.camera.models

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes

data class ResolutionOption(
    @IdRes val buttonViewId: Int,
    @DrawableRes val imageDrawableResId: Int,
)
