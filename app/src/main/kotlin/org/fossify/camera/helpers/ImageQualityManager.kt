package org.fossify.camera.helpers

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import org.fossify.camera.extensions.config
import org.fossify.camera.models.CameraSelectorImageQualities
import org.fossify.camera.models.CaptureMode
import org.fossify.camera.models.MySize
import org.fossify.commons.extensions.showErrorToast

class ImageQualityManager(private val activity: AppCompatActivity) {
    companion object {
        private val CAMERA_LENS =
            arrayOf(CameraCharacteristics.LENS_FACING_FRONT, CameraCharacteristics.LENS_FACING_BACK)
    }

    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val imageQualities = mutableListOf<CameraSelectorImageQualities>()
    private val mediaSizeStore = MediaSizeStore(activity.config)

    fun initSupportedQualities() {
        if (imageQualities.isEmpty()) {
            for (cameraId in cameraManager.cameraIdList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val lensFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
                    if (lensFacing in CAMERA_LENS) {
                        val configMap =
                            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                ?: continue
                        val standardImageSizes = configMap.getOutputSizes(ImageFormat.JPEG)
                            .map { MySize(it.width, it.height) }
                        val imageSizes = if (activity.config.captureMode == CaptureMode.MAXIMIZE_QUALITY) {
                            standardImageSizes + configMap.getHighResolutionOutputSizes(ImageFormat.JPEG)
                                .map { MySize(it.width, it.height) }
                        } else {
                            standardImageSizes
                        }
                        val cameraSelector = lensFacing.toCameraSelector()
                        imageQualities.add(CameraSelectorImageQualities(cameraSelector, imageSizes))
                    }
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                }
            }
        }
    }

    private fun Int.toCameraSelector(): CameraSelector {
        return if (this == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    fun getUserSelectedResolution(cameraSelector: CameraSelector): MySize {
        val resolutions = getSupportedResolutions(cameraSelector)
        val isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        var index = mediaSizeStore.getCurrentSizeIndex(
            isPhotoCapture = true, isFrontCamera = isFrontCamera
        )
        index = index.coerceAtMost(resolutions.lastIndex).coerceAtLeast(0)
        return resolutions[index]
    }

    fun getSupportedResolutions(cameraSelector: CameraSelector): List<MySize> {
        val fullScreenSize = getFullScreenResolution(cameraSelector) ?: return ArrayList()
        return listOf(fullScreenSize) + imageQualities.filter { it.camSelector == cameraSelector }
            .flatMap { it.qualities }
            .sortedByDescending { it.pixels }
            .distinctBy { it.getAspectRatio(activity) }
            .sortedByDescending {
                it.getAspectRatio(activity).split(":").firstOrNull()?.toIntOrNull()
            }
            .filter { it.isSupported(fullScreenSize.isSixteenToNine()) }
    }

    private fun getFullScreenResolution(cameraSelector: CameraSelector): MySize? {
        return imageQualities.filter { it.camSelector == cameraSelector }
            .flatMap { it.qualities }
            .sortedByDescending { it.width }
            .firstOrNull { it.isSupported(false) }
            ?.copy(isFullScreen = true)
    }
}
