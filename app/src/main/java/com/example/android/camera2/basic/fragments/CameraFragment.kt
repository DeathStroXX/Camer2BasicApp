
/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
//import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log

import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
//import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
//import androidx.paging.map
import com.example.android.camera.utils.ModelUtils
import com.example.android.camera.utils.SavingPixelArray
import com.example.android.camera.utils.computeExifOrientation
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera2.basic.CameraActivity
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.collections.toUByteArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.ranges.coerceIn
import kotlin.text.toByte
import kotlin.text.toUByte

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    //For reading raw image
    private lateinit var imageReaderRAW: ImageReader

    // For DEPTH\
    private lateinit var imageReaderDepth: ImageReader
    //For model inferences
    private lateinit var interpreter: Interpreter


    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            interpreter = ModelUtils.createInterpreter(requireContext(), "model.tflite")
            Log.d("Model---", "Interpreter initialized successfully.")
        } catch (e: Exception) {
            Log.e("Model--", "Failed to initialize interpreter: ${e.message}")
        }


        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )
                Log.d(TAG, "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        //Loading the model
//       interpreter = ModelUtils.createInterpreter(requireContext(), "model.tflite")
//        Log.d("Model", "Model loaded successfully.")

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE)


        // RAW ImageReader
            //ra
        val rawSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)

        if (rawSizes != null && rawSizes.isNotEmpty()) {
            val rawSize = rawSizes.maxByOrNull { it.height * it.width }!!
            imageReaderRAW = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 5)   // rawSize 2304x1728
            Log.d("RawImageReader--", "Height: ${rawSize.height} and with${rawSize.width}")

        }


        //Adding both raw and jpeg to the targets
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface, imageReaderRAW.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        Log.d("CaptureRequest", "Capture request sent.")
        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false


            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")


// Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")


                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }




                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(CameraFragmentDirections
                            .actionCameraToJpegViewer(output.absolutePath)
                            .setOrientation(result.orientation)
                            .setDepth(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    result.format == ImageFormat.DEPTH_JPEG))
                    }
                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }
        //Adding for


        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)

        val rawImageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)  // Adding for raw

//        val depthImageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)  // Ading for depth

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

//        Adding for Raw
//        imageReaderRAW.setOnImageAvailableListener({ reader ->
//            val image = reader.acquireNextImage()
//            rawImageQueue.add(image)
//        }, imageReaderHandler)

        imageReaderRAW.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            if (image != null) {
                Log.d(TAG, "RAW Image acquired: ${image.timestamp}")
                Log.d(TAG, "RAW Image acquired: Timestamp = ${image.timestamp}, Width = ${image.width}, Height = ${image.height}")
                rawImageQueue.add(image)
            } else {
                Log.e(TAG, "RAW Image is null.")
            }
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(
                            image, result, exifOrientation, imageReader.imageFormat))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }


    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                // Save JPEG file
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val jpgFile = createFile(requireContext(), "jpg")
                    FileOutputStream(jpgFile).use { it.write(bytes) }

                    cont.resume(jpgFile)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            ImageFormat.RAW_SENSOR -> {
                // Handle RAW_SENSOR image format
//
                val dngFile = createFile(requireContext(), "dng")
                try {

                    val rawImage = result.image
                    val rawBuffer = rawImage.planes[0].buffer


                    val dngCreator = DngCreator(characteristics, result.metadata)

                    FileOutputStream(dngFile).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(dngFile)

                    // Save metadata as JSON
                    val metadataFile = saveMetadata(result.metadata, dngFile)
                    Log.d(TAG, "Metadata saved: ${metadataFile.absolutePath}")

                    // Convert RAW to JPEG and save performing minor image processing
                    val (jpegFile, bitmap) = convertRawToJpeg(dngFile)
                    Log.d(TAG, "JPEG image saved: ${jpegFile.absolutePath}")

                    // Extact  RGB array from dng file

                    // Ensure the Bitmap is not null before running inference
//                    if (bitmap != null) {
//                        runInferenceOnBitmap(bitmap, interpreter)
//                    } else {
//                        Log.e("ImageProcessing", "Failed to decode RAW image to Bitmap.")
//                    }
                    val rawData = convertRawToFloatArrayFast(rawImage)
                    if (rawData != null) {
                        runInferenceOnRaw(rawData, interpreter, result)
                    }
                    rawImage.close();
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }


            }

//            ImageFormat.DEPTH_JPEG -> {
//                try {
//                    // Save the JPEG visual image
//                    val buffer = result.image.planes[0].buffer
//                    val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
//                    val jpgFile = createFile(requireContext(), "jpg")
//                    FileOutputStream(jpgFile).use { it.write(bytes) }
//                    Log.d(TAG, "JPEG saved: ${jpgFile.absolutePath}")
//
//                    // Save the depth map (second plane)
//                    val depthBuffer = result.image.planes[1].buffer
//                    val depthBytes =
//                        ByteArray(depthBuffer.remaining()).apply { depthBuffer.get(this) }
//                    val depthFile = createFile(requireContext(), "depth")
//                    FileOutputStream(depthFile).use { it.write(depthBytes) }
//                    Log.d(TAG, "Depth map saved: ${depthFile.absolutePath}")
//
//                    // Resume coroutine with the JPEG file (primary file)
//                    cont.resume(jpgFile)
//
//                } catch (exc: IOException) {
//                    Log.e(TAG, "Unable to write Depth JPEG files", exc)
//                    cont.resumeWithException(exc)
//                }
//            }

            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

// Using this function to raw image to float array which is directly comming from raw sensor & will be used for inference when sending raw data to the model


    private fun convertRawToFloatArrayFast(rawImage: Image): FloatArray? {

        try {
            if (rawImage == null) {
                Log.e("convertRawToFloat-", "rawImage is null")
                return null
            }
            val width = rawImage.width
            val height = rawImage.height
            val targetWidth = 4000
            val targetHeight = 3000

            val rawBuffer: ByteBuffer = rawImage.planes[0].buffer
            if (rawBuffer == null) {
                Log.e("convertRawToFloatAr--", "rawBuffer is null")
                return null
            }

            // Ensure the buffer is in the correct byte order (e.g., little-endian)
            rawBuffer.order(ByteOrder.LITTLE_ENDIAN)

            // Log buffer information
            Log.d("convertRawToFloatArray-", "rawBuffer capacity: ${rawBuffer.capacity()}")
            Log.d("convertRawToFloatArray-", "Image size: ${width}x${height}")

            // Create a ShortBuffer view of the ByteBuffer
            val shortBuffer: ShortBuffer = rawBuffer.asShortBuffer()

            // Read pixel values
            val shortArray = ShortArray(shortBuffer.remaining())
            shortBuffer.get(shortArray)



            // Convert to FloatArray & Normalize (0-65535 → 0.0-1.0)
            val floatArray = FloatArray(shortArray.size)
            for (i in shortArray.indices) {
                floatArray[i] = shortArray[i].toFloat() / 65535f
            }

            // Log the first 100 values or fewer
            val logValues = floatArray.take(20).joinToString(", ")  // Prevents logging too much data
            Log.d("FloatArrayValues", "First 20 values: [$logValues]")

            // Log min and max values for range verification
            val minVal = floatArray.minOrNull() ?: 0f
            val maxVal = floatArray.maxOrNull() ?: 0f
            Log.d("FloatArrayRange", "Min: $minVal, Max: $maxVal")


            // Resize only if the image is not already 3000x4000
            return if (width != targetWidth || height != targetHeight) {
                Log.d("ImageResize", "Resizing from ${width}x${height} to ${targetWidth}x${targetHeight}")
//                resizeBilinear(floatArray, width, height, targetWidth, targetHeight)
                val resizedArray = resizeBilinear(floatArray, width, height, targetWidth, targetHeight)

                // Log values after resizing
                val resizedLogValues = resizedArray.take(20).joinToString(", ")
                Log.d("FloatArrayValues_After", "First 20 values: [$resizedLogValues]")

                // Log min and max after resizing
                val resizedMin = resizedArray.minOrNull() ?: 0f
                val resizedMax = resizedArray.maxOrNull() ?: 0f
                Log.d("FloatArrayRange_After", "Min: $resizedMin, Max: $resizedMax")

                resizedArray
            } else {
                Log.d("ImageResize", "No resizing needed.")
                floatArray

            }

        } catch (e: Exception) {
            Log.e("convertRawToFloatArray-", "Exception in convertRawToFloatArrayFast", e)
            return null
        }

    }

    private fun resizeBilinear(input: FloatArray, oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): FloatArray {
        val output = FloatArray(newWidth * newHeight)

        val xRatio = oldWidth.toFloat() / newWidth
        val yRatio = oldHeight.toFloat() / newHeight

        for (newY in 0 until newHeight) {
            for (newX in 0 until newWidth) {
                val srcX = newX * xRatio
                val srcY = newY * yRatio

                val x1 = srcX.toInt()
                val y1 = srcY.toInt()
                val x2 = (x1 + 1).coerceAtMost(oldWidth - 1)
                val y2 = (y1 + 1).coerceAtMost(oldHeight - 1)

                val q11 = input[y1 * oldWidth + x1]
                val q12 = input[y2 * oldWidth + x1]
                val q21 = input[y1 * oldWidth + x2]
                val q22 = input[y2 * oldWidth + x2]

                val xDiff = srcX - x1
                val yDiff = srcY - y1

                val top = q11 * (1 - xDiff) + q21 * xDiff
                val bottom = q12 * (1 - xDiff) + q22 * xDiff

                output[newY * newWidth + newX] = top * (1 - yDiff) + bottom * yDiff
            }
        }
        return output
    }

    //created this helper function to convert raw image to jpeg ISP
    private fun convertRawToJpeg(dngFile: File): Pair<File, Bitmap?> {
        // Decode the RAW file
        val inputStream = FileInputStream(dngFile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap1 = BitmapFactory.decodeStream(inputStream, null, options)
        val bitmap = bitmap1?.let { Bitmap.createScaledBitmap(it, 2304, 1728, true) }
        if (bitmap != null) {
            Log.d("ImageResolution", "Resolution after decoding: ${bitmap.width} x ${bitmap.height}")
        }
        inputStream.close()

        // Create a new JPEG file
        val metadataFile = File(dngFile.parent, "${dngFile.nameWithoutExtension}_metadata.json")
        val jpegFile = createFile(requireContext(), "jpg")
        FileOutputStream(jpegFile).use { outputStream ->
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }

        return Pair(jpegFile, bitmap)
    }


    //Helper function created to capture all the metadata information
    private fun saveMetadata(metadata: CaptureResult, dngFile: File): File {
        val metadataMap = mutableMapOf<String, Any?>()

        for (key in metadata.keys) {
            metadataMap[key.name] = metadata.get(key)
        }

        val metadataFile = File(dngFile.parent, "${dngFile.nameWithoutExtension}_metadata.json")
        FileWriter(metadataFile).use {
            it.write(JSONObject(metadataMap).toString(4))
        }
        return metadataFile
    }
    // Function to run inferences on rgb
    fun runInferenceOnBitmap(bitmap: Bitmap, interpreter: Interpreter) {
        try {
            // Convert the Bitmap to a FloatArray for inference
            val inputArray = preprocessBitmapToModelInput(bitmap)
//            Log.d("inputArray", ${inputArray.sizes})
//            saveInputArrayAsImage(inputArray, 2304, 1728)
//            val saveArray = SavingPixelArray()
            Log.d("ModelInput", "Input array size: ${inputArray.size}")
            // Log the values in a formatted string
//            val valuesString = inputArray.joinToString(", ") { it.toString() }
//            Log.d("InputArrayValues", "Values: $valuesString")
//            saveArray.saveFloatArrayAsNumpyFile(inputArray, requireContext().filesDir, "inputArray.npy")
//            Log.d("ModelInput", "Input array saved successfully as numpy.")

            // Get the output tensor and its shape
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()

            // Calculate the total number of elements in the output tensor
            val outputSize = outputShape.reduce { acc, dim -> acc * dim }
            Log.d("OutputTensorShape", "Expected output shape: ${outputShape.joinToString(" x ")}")
            Log.d("OutputTensorSize", "Expected total elements: $outputSize")
            // Create an appropriately sized output array
            val outputArray = Array(1) { ByteArray(outputSize) }
//            val ubyteArray  =  Array(1) { UByteArray(outputSize) }
            Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")
            // Run inference
            interpreter.run(arrayOf(inputArray), outputArray)
            Log.d("Inference", "Inference completed successfully.")

            // Process the output(example: find the predicted class)
//            val predictedClass = outputArray[0].withIndex().maxByOrNull { it.value }?.index
//            Log.d("ModelPrediction", "Predicted class: $predictedClass")
            val predictedClass = outputArray[0].withIndex().maxByOrNull { it.value.toInt() and 0xFF }?.index
            Log.d("ModelPrediction", "Predicted class: $predictedClass")

            if (outputArray.isNotEmpty()) {
                Log.d("OutputCheck", "Sample Output Values: ${outputArray[0].take(10)}")
            }


            // Making changes for saving image
            val ubyteArray = outputArray.map { byteArray ->
                byteArray.map { it.toUByte() }.toUByteArray()
            }.toTypedArray()
            val outputArray1D = ubyteArray.flatMap { it.asIterable() }.toUByteArray()
            Log.d("OutputArray1D", "Flattened output array size: ${outputArray1D.size}")

            // Log a subset of the values to verify contents (first 100 values or less)
            val logValues = outputArray1D.take(100).joinToString(", ")
            Log.d("OutputArray1DValues", "First 100 values: [$logValues]")
//            saveArray.saveUByteArrayAsNumpyFile(ubyteArray, requireContext().filesDir, "OutBuytArray.npy")
            // Log min and max values
            val minVal = outputArray1D.minOf { it.toInt() }
            val maxVal = outputArray1D.maxOf { it.toInt() }
            Log.d("OutputCheck", "Min value: $minVal, Max value: $maxVal")


            // After running inference
            saveProcessedOutput(outputArray1D, outputShape)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }

    // Function to run inferences on raw values extracted
//    fun runInferenceOnRaw(inputArray: FloatArray, interpreter: Interpreter, result: CombinedCaptureResult) {
//        try {
//
//            // Get the output tensor and its shape
//            val outputTensor = interpreter.getOutputTensor(0)
//            val outputShape = outputTensor.shape()
//            val expectedOutputSize = outputShape.reduce { acc, dim -> acc * dim }
//            Log.d("OutputTensorShape", "Expected output shape: ${outputShape.joinToString(" x ")}")
//            Log.d("OutputTensorSize", "Expected total elements: $expectedOutputSize")
//
//            // Calculate the total number of elements in the output tensor
////            val outputSize = outputShape.reduce { acc, dim -> acc * dim }
//
//            // Create an appropriately sized output array
//            val outputArray = Array(1) { ByteArray(expectedOutputSize) }
////            val ubyteArray  =  Array(1) { UByteArray(outputSize) }
//            Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")
//            // Run inference
//            interpreter.run(arrayOf(inputArray), outputArray)
//            Log.d("Inference", "Inference completed successfully.")
//
//            // Process the output(example: find the predicted class)
////            val predictedClass = outputArray[0].withIndex().maxByOrNull { it.value }?.index
////            Log.d("ModelPrediction", "Predicted class: $predictedClass")
//            val predictedClass = outputArray[0].withIndex().maxByOrNull { it.value.toInt() and 0xFF }?.index
//            Log.d("ModelPrediction", "Predicted class: $predictedClass")
//
//            if (outputArray.isNotEmpty()) {
//                Log.d("OutputCheck", "Sample Output Values: ${outputArray[0].take(10)}")
//            }
//
//
//            // Making changes for saving image
//            val ubyteArray = outputArray.map { byteArray ->
//                byteArray.map { it.toUByte() }.toUByteArray()
//            }.toTypedArray()
//            val outputArray1D = ubyteArray.flatMap { it.asIterable() }.toUByteArray()
//            Log.d("OutputArray1D", "Flattened output array size: ${outputArray1D.size}")
//
//            // Log a subset of the values to verify contents (first 100 values or less)
//            val logValues = outputArray1D.take(100).joinToString(", ")
//            Log.d("OutputArray1DValues", "First 100 values: [$logValues]")
////            saveArray.saveUByteArrayAsNumpyFile(ubyteArray, requireContext().filesDir, "OutBuytArray.npy")
//            // Log min and max values
//            val minVal = outputArray1D.minOf { it.toInt() }
//            val maxVal = outputArray1D.maxOf { it.toInt() }
//            Log.d("OutputCheck", "Min value: $minVal, Max value: $maxVal")
//
//
//            // After running inference
//            saveRawProcessedOutput(outputArray1D, outputShape, result)
////            saveProcessedOutput(outputShapeutArray1D, outputShape)
//
//
//        } catch (e: Exception) {
//            Log.e("ModelError", "Error during inference: ${e.message}")
//        }
//    }

    fun runInferenceOnRaw(inputArray: FloatArray, interpreter: Interpreter, result: CombinedCaptureResult) {
        try {

            //Get input tensor info
            val inputTensor = interpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            Log.d("InputTensorShape", "Input shape: ${inputShape.joinToString(",")}")

            val inputBufferProvided = inputArray.take(20).joinToString(", ")
            Log.d("Provided Input toModel", "First 20 values of inputArray: [$inputBufferProvided]")
            Log.d("InputTensorShape", "Shape of inputArray to model: ${inputShape.joinToString(",")}")

            // Get the output tensor and its shape
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val expectedOutputSize = outputShape.reduce { acc, dim -> acc * dim }
            Log.d("OutputTensorShape", "Expected output shape: ${outputShape.joinToString(" x ")}")
            Log.d("OutputTensorSize", "Expected total elements: $expectedOutputSize")

            // Create an appropriately sized output array
            val outputArray = Array(1) { FloatArray(expectedOutputSize) }
            Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")

            // Run inference
            interpreter.run(arrayOf(inputArray), outputArray)
            Log.d("Inference", "Inference completed successfully.")

            // Flatten the output array (1D FloatArray)
            val outputArray1D: FloatArray = if (outputArray.isNotEmpty()) {
                val innerArray = outputArray[0]
                FloatArray(innerArray.size) { i -> innerArray[i] }
            } else {
                FloatArray(0)
            }
            Log.d("OutputArray1D", "Flattened output array size: ${outputArray1D.size}")

            // Log a subset of the values to verify contents (first 100 values or less)
            val logValues = outputArray1D.take(100).joinToString(", ")
            Log.d("OutputArray1DValues", "First 100 values: [$logValues]")

            // Log min and max values
            val minVal = outputArray1D.minOrNull() ?: 0f
            val maxVal = outputArray1D.maxOrNull() ?: 1f
            Log.d("OutputCheck", "Min value: $minVal, Max value: $maxVal")

            // Save the processed output as a DNG file
            saveRawProcessedOutput(outputArray1D, outputShape, result)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }

    // Assuming you have a preprocessBitmapToModelInput function that converts the Bitmap

    private fun preprocessBitmapToModelInput(bitmap: Bitmap): FloatArray {

        // Resize the bitmap to the model's expected input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 2304, 1728, true)
        Log.d("Preprocess", "Bitmap resized to: ${resizedBitmap.width}x${resizedBitmap.height}")

        // Initialize the FloatArray for the RGB input
        val floatArray = FloatArray(2304 * 1728 * 3) // For RGB input
        var index = 0

        // Debugging variables to track pixel values
        var minPixelValue = Float.MAX_VALUE
        var maxPixelValue = Float.MIN_VALUE
        Log.d("Model Input--------", "Minumum Pixel value: ${minPixelValue}; Maximum Pixel value: ${maxPixelValue}")

        for (y in 0 until resizedBitmap.height) {
            for (x in 0 until resizedBitmap.width) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = (pixel shr 16 and 0xFF) / 255.0f // Red
                val g = (pixel shr 8 and 0xFF) / 255.0f  // Green
                val b = (pixel and 0xFF) / 255.0f        // Blue

                // Store the normalized RGB values in the float array
                floatArray[index++] = r
                floatArray[index++] = g
                floatArray[index++] = b

                // Update min and max for debugging
                minPixelValue = minOf(minPixelValue, r, g, b)
                maxPixelValue = maxOf(maxPixelValue, r, g, b)
            }
        }

        // Log statistics about the processed input
        Log.d("Preprocess", "Preprocessed float array created.")
        Log.d("Preprocess", "Total values: ${floatArray.size}, Min: $minPixelValue, Max: $maxPixelValue")

        // Log a sample of the normalized pixel values
        Log.d("PreprocessSample", "Sample values: ${floatArray.take(10)}")


        return floatArray
    }

    private fun saveProcessedOutput(outputArrayValue: UByteArray, outputShape: IntArray) {
        // Assuming outputShape is [Batch, Channels, Height, Width]
        val batchSize = outputShape[0] // Should be 1
        val channels = outputShape[1] // RGB = 3
        val height = outputShape[2]   // 1728
        val width = outputShape[3]    // 2304

        Log.d("ModelProcessOutput ----", "Output shape: Batch=$batchSize, Channels=$channels, Height=$height, Width=$width")

        // Ensure the output has 3 channels (RGB)
        //        // Initialize min and max values for debugging
        var minValue = 255
        var maxValue = 0
        // Convert UByteArray to FloatArray
        val floatArray = FloatArray(outputArrayValue.size) { i ->
            outputArrayValue[i].toFloat() / 255.0f // Assuming normalized between 0 and 1
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var index = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Extract RGB values from the FloatArray
                val r = (floatArray[index++] * 255).toInt().coerceIn(0, 255)
                val g = (floatArray[index++] * 255).toInt().coerceIn(0, 255)
                val b = (floatArray[index++] * 255).toInt().coerceIn(0, 255)

                // Reconstruct the pixel color
                val color = (255 shl 24) or (r shl 16) or (g shl 8) or b
                bitmap.setPixel(x, y, color)
            }
        }
        Log.d("ModelProcessOutput ----", "Pixel value range in bitmap: Min=$minValue, Max=$maxValue")

        // Save the bitmap as a file
        try {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val fileName = "IMG_${sdf.format(Date())}_ProcessedOutput.jpg"
            val file = File(requireContext().filesDir, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }
            Log.d("ModelProcessOutput ----", "Processed image saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ModelProcessOutput ----", "Failed to save processed image: ${e.message}", e)
        }
    }
    private fun saveRawProcessedOutput(


        floatArray: FloatArray,  // Model output (normalized 0.0 - 1.0)
        dimensions: IntArray,     // Expected [1, C, H, W] (C=1 or 4)
//
        result: CombinedCaptureResult
    ) {
        try {
            val channels = dimensions[1]  // Number of channels (1 for RAW, 4 for RGBA)
            val height = dimensions[2]    // Image height
            val width = dimensions[3]     // Image width

            Log.d("DNG-Save", "Saving DNG with size: $width x $height, Channels: $channels")

            // Convert FloatArray to 16-bit RAW ByteArray
            val byteBuffer = ByteBuffer.allocate(floatArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (value in floatArray) {
                val scaledValue = (value * 65535).toInt().coerceIn(0, 65535)  // Normalize 0.0 - 1.0 to 0 - 65535
                byteBuffer.putShort(scaledValue.toShort())
            }

            // Create ImageReader for RAW storage
            val format = if (channels == 1) android.graphics.ImageFormat.RAW_SENSOR else android.graphics.ImageFormat.FLEX_RGBA_8888
            val imageReader = ImageReader.newInstance(width, height, format, 1)

            // Acquire an image to store the processed data
            val image = imageReader.acquireNextImage()
            image?.planes?.get(0)?.buffer?.put(byteBuffer.array())

            // Create a DNG file in the app's private storage
//            val dngFile = File(File("/data/data/com.yourapp/files/"), "processed_output.dng")

            // Save using DngCreator
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val fileName = "IMG_Raw_Processed${sdf.format(Date())}.dng"
            val dngFile = File(requireContext().filesDir, fileName)

//            val dngFile = createFile(requireContext(), "dng")
            val dngCreator = DngCreator(characteristics, result.metadata)
            FileOutputStream(dngFile).use { dngCreator.writeImage(it, image) }

            Log.d("DNG-Save", "DNG file saved at: ${dngFile.absolutePath}")

            // Release resources
            image.close()
            imageReader.close()
        } catch (e: Exception) {
            Log.e("DNG-Save", "Error saving DNG file: ${e.message}")
        }
    }

    private fun convertRawToFloatArray(
        rawData: ByteArray,  // Raw image data from the sensor
        width: Int,
        height: Int
    ): FloatArray {
        // Create a single-channel float array to store the raw data
        val singleChannelArray = FloatArray(width * height)

        var pixelIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Convert each raw byte to float and store it in the single-channel array
                // Assuming rawData contains byte values and we normalize them to float [0, 1]
                val rawValue = (rawData[pixelIndex].toInt() and 0xFF).toFloat() / 255.0f
                singleChannelArray[pixelIndex] = rawValue
                pixelIndex++
            }
        }

        return singleChannelArray
    }





    private fun saveInputArrayAsImage(inputArray: FloatArray, width: Int, height: Int) {
        try {
            // Create a Bitmap to store the pixel data

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            var index = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Extract RGB values from the FloatArray
                    val r = (inputArray[index++] * 255).toInt().coerceIn(0, 255)
                    val g = (inputArray[index++] * 255).toInt().coerceIn(0, 255)
                    val b = (inputArray[index++] * 255).toInt().coerceIn(0, 255)

                    // Reconstruct the pixel color
                    val color = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    bitmap.setPixel(x, y, color)
                }
            }

            // Save the reconstructed Bitmap to a file
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val outputFile = File(requireContext().filesDir, "IMG_${sdf.format(Date())}_Input.jpg")
            FileOutputStream(outputFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            Log.d("SaveImage", "Input array saved as image: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("SaveImageError", "Failed to save input array as image: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()

        interpreter.close()
        Log.d("Model", "Interpreter closed.")
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }



    }
}
