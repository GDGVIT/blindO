package com.minosai.blindo

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.Fragment
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.TextView
import android.widget.Toast
import com.minosai.blindo.utils.AutoFitTextureView
import kotlinx.android.synthetic.main.fragment_main_camera.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Created by minos.ai on 03/04/18.
 */

class MainCameraFragment : Fragment() {

    private val TAG = "MainCameraFragment"

    private val PERMISSION_REQUEST_CODE = 100

    private val MAIN_THREAD_NAME = "MainCameraFragment"
    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080

    internal var captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
        }
    }

    private var captureSession: CameraCaptureSession? = null
    private val lock = Any()
    private var runClassifier = false
    private var checkPermission = false
    private var imageClassifier: ImageClassifier? = null
    private var cameraId: String? = null
    private val autoFitTextureView: AutoFitTextureView? = null
    private var cameraDevice: CameraDevice? = null
    private val cameraCaptureSession: CameraCaptureSession? = null
    private var previewSize: Size? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null


    private val cameraOpenCloseLock = Semaphore(1)

    internal var stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(currentDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = currentDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(currentDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            currentDevice.close()
            cameraDevice = null
        }

        override fun onError(currentDevice: CameraDevice, i: Int) {
            cameraOpenCloseLock.release()
            currentDevice.close()
            cameraDevice = null
            val activity = activity
            activity?.finish()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            openCamera(height, width)
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {

        }
    }

    private val periodicClassify = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (runClassifier) {
                    classifyFrame()
                }
            }
            backgroundHandler?.post(this)
        }
    }

    private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<Size>()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth
                    && option.height <= maxHeight
                    && option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size > 0) {
            return Collections.min(bigEnough, CompareSizesByArea())
        } else if (notBigEnough.size > 0) {
            return Collections.max(notBigEnough, CompareSizesByArea())
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size")
            return choices[0]
        }
    }

    fun newInstance(): Fragment {
        return MainCameraFragment()
    }

//    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle) = inflater.inflate(R.layout.fragment_main_camera, container, false)

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?) = inflater?.inflate(R.layout.fragment_main_camera, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        try {
            imageClassifier = ImageClassifier(activity)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize image classifier")
            Toast.makeText(context, "Fauiled to initalize image classifier", Toast.LENGTH_SHORT).show()
        }

        startBackgroundThread()
    }

    override fun onResume() {
        super.onResume()

        startBackgroundThread()

        if (texture.isAvailable) {
            openCamera(texture.width, texture.height)
        } else {
            texture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.let {
                captureSession?.close()
                captureSession = null
            }
            cameraDevice?.let {
                cameraDevice?.close()
                cameraDevice = null
            }
            imageReader?.let {
                imageReader?.close()
                imageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    override fun onDestroy() {
        imageClassifier?.close()
        super.onDestroy()
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        val activity = activity
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea())
                imageReader = ImageReader.newInstance(
                        largest.width, largest.height, ImageFormat.JPEG, /*maxImages*/ 2)

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity.windowManager.defaultDisplay.rotation

                /* Orientation of the camera sensor */
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                var swappedDimensions = false
                when (displayRotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation == 90 || sensorOrientation == 270) {
                        swappedDimensions = true
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation == 0 || sensorOrientation == 180) {
                        swappedDimensions = true
                    }
                    else -> Log.e(TAG, "Display rotation is invalid: $displayRotation")
                }

                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width
                    maxPreviewWidth = displaySize.y
                    maxPreviewHeight = displaySize.x
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }

                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth,
                        rotatedPreviewHeight,
                        maxPreviewWidth,
                        maxPreviewHeight,
                        largest)

                // We fit the aspect ratio of TextureView to the size of preview we picked.
//                val orientation = resources.configuration.orientation
//                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                    textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight())
//                    text
//                } else {
//                    textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth())
//                }

                this.cameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            Log.e(TAG, e.localizedMessage)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val activity = activity
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(MAIN_THREAD_NAME)
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.getLooper())
        synchronized(lock) {
            runClassifier = true
        }
        backgroundHandler?.post(periodicClassify)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            synchronized(lock) {
                runClassifier = false
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }

    private fun createCameraPreviewSession() {
        try {
            val texture = texture.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture!!.setDefaultBufferSize(previewSize!!.getWidth(), previewSize!!.getHeight())

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(
                    Arrays.asList(surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder?.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder?.build()
                                captureSession?.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }

                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == texture || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize?.getHeight()!!.toFloat(), previewSize?.getWidth()!!.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize!!.getHeight(),
                    viewWidth.toFloat() / previewSize!!.getWidth())
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        texture.setTransform(matrix)
    }

    private fun classifyFrame() {
        if (imageClassifier == null || activity == null || cameraDevice == null) {
            Toast.makeText(context, "Uninitialized Classifier or invalid context.", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = texture.getBitmap(ImageClassifier.DIM_IMG_SIZE_X, ImageClassifier.DIM_IMG_SIZE_Y)
        val textToShow = imageClassifier?.classifyFrame(bitmap)
        bitmap?.recycle()
        Toast.makeText(context, textToShow, Toast.LENGTH_SHORT).show()
    }

    private class CompareSizesByArea : Comparator<Size> {

        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                    lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }
}