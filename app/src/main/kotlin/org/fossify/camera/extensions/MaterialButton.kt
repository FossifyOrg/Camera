package org.fossify.camera.extensions

import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import org.fossify.camera.R
import org.fossify.camera.views.ShadowDrawable

fun MaterialButton.setShadowIcon(@DrawableRes drawableResId: Int) {
    icon = ShadowDrawable(context, drawableResId, R.style.TopIconShadow)
}
