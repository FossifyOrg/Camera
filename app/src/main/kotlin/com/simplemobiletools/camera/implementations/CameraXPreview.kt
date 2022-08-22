package com.simplemobiletools.camera.implementations

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.util.Rational
import android.util.Size
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.ScaleType
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.bumptech.glide.load.ImageHeaderParser.UNKNOWN_ORIENTATION
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.dialogs.ChangeResolutionDialogX
import com.simplemobiletools.camera.extensions.*
import com.simplemobiletools.camera.helpers.*
import com.simplemobiletools.camera.interfaces.MyPreview
import com.simplemobiletools.camera.models.MediaOutput
import com.simplemobiletools.camera.models.MySize
import com.simplemobiletools.camera.models.ResolutionOption
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import kotlin.math.abs

class CameraXPreview(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val mediaOutputHelper: MediaOutputHelper,
    private val cameraErrorHandler: CameraErrorHandler,
    private val listener: CameraXPreviewListener,
    initInPhotoMode: Boolean,
) : MyPreview, DefaultLifecycleObserver {

    companion object {
        // Auto focus is 1/6 of the area.
        private const val AF_SIZE = 1.0f / 6.0f
        private const val AE_SIZE = AF_SIZE * 1.5f
        private const val MIN_SWIPE_DISTANCE_X = 100
    }

    private val config = activity.config
    private val contentResolver = activity.contentResolver
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mediaSoundHelper = MediaSoundHelper()
    private val windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()
    private val videoQualityManager = VideoQualityManager(activity)
    private val imageQualityManager = ImageQualityManager(activity)
    private val exifRemover = ExifRemover(contentResolver)

    private val orientationEventListener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
        @SuppressLint("RestrictedApi")
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == UNKNOWN_ORIENTATION) {
                return
            }

            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }

            if (lastRotation != rotation) {
                preview?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
                lastRotation = rotation
            }
        }
    }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var currentRecording: Recording? = null
    private var recordingState: VideoRecordEvent? = null
    private var cameraSelector = config.lastUsedCameraLens.toCameraSelector()
    private var flashMode = FLASH_MODE_OFF
    private var isPhotoCapture = initInPhotoMode
    private var lastRotation = 0

    init {
        bindToLifeCycle()
        mediaSoundHelper.loadSounds()
        previewView.doOnLayout {
            startCamera()
        }
    }

    private fun bindToLifeCycle() {
        activity.lifecycle.addObserver(this)
    }

    private fun startCamera(switching: Boolean = false) {
        imageQualityManager.initSupportedQualities()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                videoQualityManager.initSupportedQualities(provider)
                bindCameraUseCases()
                setupCameraObservers()
            } catch (e: Exception) {
                val errorMessage = if (switching) R.string.camera_switch_error else R.string.camera_open_error
                activity.toast(errorMessage)
            }
        }, mainExecutor)
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val resolution = if (isPhotoCapture) {
            imageQualityManager.getUserSelectedResolution(cameraSelector).also {
                displaySelectedResolution(it.toResolutionOption())
            }
        } else {
            val selectedQuality = videoQualityManager.getUserSelectedQuality(cameraSelector).also {
                displaySelectedResolution(it.toResolutionOption())
            }
            MySize(selectedQuality.width, selectedQuality.height)
        }

        val isFullSize = resolution.isFullScreen
        previewView.scaleType = if (isFullSize) ScaleType.FILL_CENTER else ScaleType.FIT_CENTER
        val rotation = previewView.display.rotation
        val rotatedResolution = getRotatedResolution(resolution, rotation)

        val previewUseCase = buildPreview(rotatedResolution, rotation)
        val captureUseCase = getCaptureUseCase(rotatedResolution, rotation)

        cameraProvider.unbindAll()
        camera = if (isFullSize) {
            val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(activity).bounds
            val screenWidth = metrics.width()
            val screenHeight = metrics.height()
            val viewPort = ViewPort.Builder(Rational(screenWidth, screenHeight), rotation).build()

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(captureUseCase)
                .setViewPort(viewPort)
                .build()

            cameraProvider.bindToLifecycle(
                activity,
                cameraSelector,
                useCaseGroup,
            )
        } else {
            cameraProvider.bindToLifecycle(
                activity,
                cameraSelector,
                previewUseCase,
                captureUseCase,
            )
        }

        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        preview = previewUseCase
        setupZoomAndFocus()
    }

    private fun displaySelectedResolution(resolutionOption: ResolutionOption) {
        listener.displaySelectedResolution(resolutionOption)
    }

    private fun getRotatedResolution(resolution: MySize, rotationDegrees: Int): Size {
        return if (rotationDegrees == Surface.ROTATION_0 || rotationDegrees == Surface.ROTATION_180) {
            Size(resolution.height, resolution.width)
        } else {
            Size(resolution.width, resolution.height)
        }
    }

    private fun buildPreview(resolution: Size, rotation: Int): Preview {
        return Preview.Builder()
            .setTargetRotation(rotation)
            .setTargetResolution(resolution)
            .build()
    }

    private fun getCaptureUseCase(resolution: Size, rotation: Int): UseCase {
        return if (isPhotoCapture) {
            buildImageCapture(resolution, rotation).also {
                imageCapture = it
            }
        } else {
            buildVideoCapture().also {
                videoCapture = it
            }
        }
    }

    private fun buildImageCapture(resolution: Size, rotation: Int): ImageCapture {
        return Builder()
            .setCaptureMode(CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(config.photoQuality)
            .setTargetRotation(rotation)
            .setTargetResolution(resolution)
            .build()
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            videoQualityManager.getUserSelectedQuality(cameraSelector).toCameraXQuality(),
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    private fun setupCameraObservers() {
        listener.setFlashAvailable(camera?.cameraInfo?.hasFlashUnit() ?: false)
        listener.onChangeCamera(isFrontCameraInUse())

        camera?.cameraInfo?.cameraState?.observe(activity) { cameraState ->
            when (cameraState.type) {
                CameraState.Type.OPEN,
                CameraState.Type.OPENING -> {
                    listener.setHasFrontAndBackCamera(hasFrontCamera() && hasBackCamera())
                    listener.setCameraAvailable(true)
                }
                CameraState.Type.PENDING_OPEN,
                CameraState.Type.CLOSING,
                CameraState.Type.CLOSED -> {
                    listener.setCameraAvailable(false)
                }
            }

            cameraErrorHandler.handleCameraError(cameraState?.error)
        }
    }

    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun isFrontCameraInUse(): Boolean {
        return cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    @SuppressLint("ClickableViewAccessibility")
    // source: https://stackoverflow.com/a/60095886/10552591
    private fun setupZoomAndFocus() {
        val scaleGesture = camera?.let { ScaleGestureDetector(activity, PinchToZoomOnScaleGestureListener(it.cameraInfo, it.cameraControl)) }
        val gestureDetector = GestureDetector(activity, object : SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent?): Boolean {
                listener.onTouchPreview()
                return super.onDown(e)
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                return camera?.cameraInfo?.let {
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val width = previewView.width.toFloat()
                    val height = previewView.height.toFloat()
                    val factory = DisplayOrientedMeteringPointFactory(display, it, width, height)
                    val xPos = event.x
                    val yPos = event.y
                    val autoFocusPoint = factory.createPoint(xPos, yPos, AF_SIZE)
                    val autoExposurePoint = factory.createPoint(xPos, yPos, AE_SIZE)
                    val focusMeteringAction = FocusMeteringAction.Builder(autoFocusPoint, FocusMeteringAction.FLAG_AF)
                        .addPoint(autoExposurePoint, FocusMeteringAction.FLAG_AE)
                        .disableAutoCancel()
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(focusMeteringAction)
                    listener.onFocusCamera(xPos, yPos)
                    true
                } ?: false
            }

            override fun onFling(event1: MotionEvent, event2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val deltaX = event1.x - event2.x
                val deltaXAbs = abs(deltaX)

                if (deltaXAbs >= MIN_SWIPE_DISTANCE_X) {
                    if (deltaX > 0) {
                        listener.onSwipeLeft()
                    } else {
                        listener.onSwipeRight()
                    }
                }

                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            val handledGesture = gestureDetector.onTouchEvent(event)
            val handledScaleGesture = scaleGesture?.onTouchEvent(event)
            handledGesture || handledScaleGesture ?: false
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        orientationEventListener.enable()
    }

    override fun onStop(owner: LifecycleOwner) {
        orientationEventListener.disable()
    }

    override fun showChangeResolutionDialog() {
        val oldQuality = videoQualityManager.getUserSelectedQuality(cameraSelector)
        ChangeResolutionDialogX(
            activity,
            isFrontCameraInUse(),
            imageQualityManager.getSupportedResolutions(cameraSelector),
            videoQualityManager.getSupportedQualities(cameraSelector),
        ) {
            if (oldQuality != videoQualityManager.getUserSelectedQuality(cameraSelector)) {
                currentRecording?.stop()
            }
            startCamera()
        }
    }

    override fun showChangeResolution() {
        val imageSizes = imageQualityManager.getSupportedResolutions(cameraSelector)
        val videoSizes = videoQualityManager.getSupportedQualities(cameraSelector)
        val selectedVideoSize = videoQualityManager.getUserSelectedQuality(cameraSelector)
        val selectedImageSize = imageQualityManager.getUserSelectedResolution(cameraSelector)

        val selectedResolution = if (isPhotoCapture) selectedImageSize.toResolutionOption() else selectedVideoSize.toResolutionOption()
        val resolutions = if (isPhotoCapture) imageSizes.map { it.toResolutionOption() } else videoSizes.map { it.toResolutionOption() }

        listener.showImageSizes(
            selectedResolution = selectedResolution,
            resolutions = resolutions,
            isPhotoCapture = isPhotoCapture,
            isFrontCamera = isFrontCameraInUse()
        ) { changed ->
            if (changed) {
                currentRecording?.stop()
            }
            startCamera()
        }
    }

    override fun toggleFrontBackCamera() {
        val newCameraSelector = if (isFrontCameraInUse()) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        cameraSelector = newCameraSelector
        config.lastUsedCameraLens = newCameraSelector.toLensFacing()
        startCamera(switching = true)
    }

    override fun setFlashlightState(state: Int) {
        val newFlashMode = state.toCameraXFlashMode()
        if (!isPhotoCapture) {
            camera?.cameraControl?.enableTorch(newFlashMode == FLASH_MODE_ON)
        }
        flashMode = newFlashMode
        imageCapture?.flashMode = newFlashMode
        val appFlashMode = flashMode.toAppFlashMode()
        config.flashlightState = appFlashMode
        listener.onChangeFlashMode(appFlashMode)
    }

    override fun tryTakePicture() {
        val imageCapture = imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        val metadata = Metadata().apply {
            isReversedHorizontal = isFrontCameraInUse() && config.flipPhotos
        }

        val mediaOutput = mediaOutputHelper.getImageMediaOutput()

        if (mediaOutput is MediaOutput.BitmapOutput) {
            imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    listener.toggleBottomButtons(false)
                    val bitmap = BitmapUtils.makeBitmap(image.toJpegByteArray())
                    if (bitmap != null) {
                        listener.onImageCaptured(bitmap)
                    } else {
                        cameraErrorHandler.handleImageCaptureError(ERROR_CAPTURE_FAILED)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    handleImageCaptureError(exception)
                }
            })
        } else {
            val outputOptionsBuilder = when (mediaOutput) {
                is MediaOutput.MediaStoreOutput -> OutputFileOptions.Builder(contentResolver, mediaOutput.contentUri, mediaOutput.contentValues)
                is MediaOutput.OutputStreamMediaOutput -> OutputFileOptions.Builder(mediaOutput.outputStream)
                is MediaOutput.BitmapOutput -> throw IllegalStateException("Cannot produce an OutputFileOptions for a bitmap output")
                else -> throw IllegalArgumentException("Unexpected option for image ")
            }

            val outputOptions = outputOptionsBuilder.setMetadata(metadata).build()

            imageCapture.takePicture(outputOptions, mainExecutor, object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: OutputFileResults) {
                    ensureBackgroundThread {
                        val savedUri = mediaOutput.uri ?: outputFileResults.savedUri!!
                        if (!config.savePhotoMetadata) {
                            exifRemover.removeExif(savedUri)
                        }

                        activity.runOnUiThread {
                            listener.toggleBottomButtons(false)
                            listener.onMediaSaved(savedUri)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    handleImageCaptureError(exception)
                }
            })
        }
        playShutterSoundIfEnabled()
    }

    private fun handleImageCaptureError(exception: ImageCaptureException) {
        listener.toggleBottomButtons(false)
        cameraErrorHandler.handleImageCaptureError(exception.imageCaptureError)
    }

    override fun initPhotoMode() {
        isPhotoCapture = true
        startCamera()
    }

    override fun initVideoMode() {
        isPhotoCapture = false
        startCamera()
    }

    override fun toggleRecording() {
        if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
            startRecording()
        } else {
            currentRecording?.stop()
            currentRecording = null
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startRecording() {
        val videoCapture = videoCapture ?: throw IllegalStateException("Camera initialization failed.")

        val mediaOutput = mediaOutputHelper.getVideoMediaOutput()
        val recording = when (mediaOutput) {
            is MediaOutput.FileDescriptorMediaOutput -> {
                FileDescriptorOutputOptions.Builder(mediaOutput.fileDescriptor).build()
                    .let { videoCapture.output.prepareRecording(activity, it) }
            }
            is MediaOutput.FileMediaOutput -> {
                FileOutputOptions.Builder(mediaOutput.file).build()
                    .let { videoCapture.output.prepareRecording(activity, it) }
            }
            is MediaOutput.MediaStoreOutput -> {
                MediaStoreOutputOptions.Builder(contentResolver, mediaOutput.contentUri).setContentValues(mediaOutput.contentValues).build()
                    .let { videoCapture.output.prepareRecording(activity, it) }
            }
            else -> throw IllegalArgumentException("Unexpected output option for video $mediaOutput")
        }

        currentRecording = recording.withAudioEnabled()
            .start(mainExecutor) { recordEvent ->
                recordingState = recordEvent
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        playStartVideoRecordingSoundIfEnabled()
                        listener.onVideoRecordingStarted()
                    }

                    is VideoRecordEvent.Status -> {
                        listener.onVideoDurationChanged(recordEvent.recordingStats.recordedDurationNanos)
                    }

                    is VideoRecordEvent.Finalize -> {
                        playStopVideoRecordingSoundIfEnabled()
                        listener.onVideoRecordingStopped()
                        if (recordEvent.hasError()) {
                            cameraErrorHandler.handleVideoRecordingError(recordEvent.error)
                        } else {
                            listener.onMediaSaved(mediaOutput.uri ?: recordEvent.outputResults.outputUri)
                        }
                    }
                }
            }
    }

    private fun playShutterSoundIfEnabled() {
        if (config.isSoundEnabled) {
            mediaSoundHelper.playShutterSound()
        }
    }

    private fun playStartVideoRecordingSoundIfEnabled() {
        if (config.isSoundEnabled) {
            mediaSoundHelper.playStartVideoRecordingSound()
        }
    }

    private fun playStopVideoRecordingSoundIfEnabled() {
        if (config.isSoundEnabled) {
            mediaSoundHelper.playStopVideoRecordingSound()
        }
    }
}
