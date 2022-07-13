package com.simplemobiletools.camera.helpers

import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import com.simplemobiletools.camera.extensions.config
import com.simplemobiletools.camera.extensions.toCameraXQuality
import com.simplemobiletools.camera.extensions.toVideoQuality
import com.simplemobiletools.camera.models.CameraSelectorVideoQualities
import com.simplemobiletools.camera.models.VideoQuality
import com.simplemobiletools.commons.extensions.showErrorToast

class VideoQualityManager(
    private val activity: AppCompatActivity,
) {

    companion object {
        private val QUALITIES = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        private val CAMERA_SELECTORS = arrayOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
    }

    private val config = activity.config
    private val videoQualities = mutableListOf<CameraSelectorVideoQualities>()

    fun initSupportedQualities(cameraProvider: ProcessCameraProvider) {
        if (videoQualities.isEmpty()) {
            for (camSelector in CAMERA_SELECTORS) {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(activity, camSelector)
                try {
                    if (cameraProvider.hasCamera(camSelector)) {
                        QualitySelector.getSupportedQualities(camera.cameraInfo)
                            .filter(QUALITIES::contains)
                            .also { allQualities ->
                                val qualities = allQualities.map { it.toVideoQuality() }
                                videoQualities.add(CameraSelectorVideoQualities(camSelector, qualities))
                            }
                    }
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                }
            }
        }
    }

    fun getUserSelectedQuality(cameraSelector: CameraSelector): Quality {
        var selectionIndex = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) config.frontVideoResIndex else config.backVideoResIndex
        selectionIndex = selectionIndex.coerceAtLeast(0)
        return getSupportedQualities(cameraSelector).getOrElse(selectionIndex) { VideoQuality.HD }.toCameraXQuality()
    }

    fun getSupportedQualities(cameraSelector: CameraSelector): List<VideoQuality> {
        return videoQualities.filter { it.camSelector == cameraSelector }
            .flatMap { it.qualities }
            .sortedByDescending { it.pixels }
    }
}
