//package functionalities
//
//import android.content.ContentValues
//import android.content.Context
//import android.hardware.camera2.DngCreator
//import android.media.Image
//import android.media.ImageReader
//import android.os.Build
//import android.os.Environment
//import android.provider.MediaStore
//import android.util.Log
//import org.tensorflow.lite.Interpreter
//import java.io.File
//import java.io.FileOutputStream
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.nio.ShortBuffer
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//
//object Raw2RawModelPipeline {
//
//     fun convertRawToFloatArrayFast(rawImage: Image, context: Context): FloatArray? {
//
//        try {
//            if (rawImage == null) {
//                Log.e("convertRawToFloat-", "rawImage is null")
//                return null
//            }
//            val width = rawImage.width
//            val height = rawImage.height
//            val targetWidth = 4000
//            val targetHeight = 3000
//
//            val rawBuffer: ByteBuffer = rawImage.planes[0].buffer
//            if (rawBuffer == null) {
//                Log.e("convertRawToFloatAr--", "rawBuffer is null")
//                return null
//            }
//
//            // Ensure the buffer is in the correct byte order (e.g., little-endian)
//            rawBuffer.order(ByteOrder.LITTLE_ENDIAN)
//
//            // Log buffer information
//            Log.d("convertRawToFloatArray-", "rawBuffer capacity: ${rawBuffer.capacity()}")
//            Log.d("convertRawToFloatArray-", "Image size: ${width}x${height}")
//
//            // Create a ShortBuffer view of the ByteBuffer
//            val shortBuffer: ShortBuffer = rawBuffer.asShortBuffer()
//
//            // Read pixel values
//            val shortArray = ShortArray(shortBuffer.remaining())
//            shortBuffer.get(shortArray)
//
//
//
//            // Convert to FloatArray & Normalize (0-65535 → 0.0-1.0)
//            val floatArray = FloatArray(shortArray.size)
//            for (i in shortArray.indices) {
//                floatArray[i] = shortArray[i].toFloat() / 65535f
//            }
//
//            // Log the first 100 values or fewer
//            val logValues = floatArray.take(20).joinToString(", ")  // Prevents logging too much data
//            Log.d("FloatArrayValues", "First 20 values: [$logValues]")
//
//            // Log min and max values for range verification
//            val minVal = floatArray.minOrNull() ?: 0f
//            val maxVal = floatArray.maxOrNull() ?: 0f
//            Log.d("FloatArrayRange", "Min: $minVal, Max: $maxVal")
//
//
//            // Resize only if the image is not already 3000x4000
//            return if (width != targetWidth || height != targetHeight) {
//                Log.d("ImageResize", "Resizing from ${width}x${height} to ${targetWidth}x${targetHeight}")
////                resizeBilinear(floatArray, width, height, targetWidth, targetHeight)
////                val resizedArray = resizeBilinear(floatArray, width, height, targetWidth, targetHeight)
//                val resizedArray = floatArray
//
//                // Log values after resizing
//                val resizedLogValues = resizedArray.take(20).joinToString(", ")
//                Log.d("FloatArrayValues_After", "First 20 values: [$resizedLogValues]")
//
//                // Log min and max after resizing
//                val resizedMin = resizedArray.minOrNull() ?: 0f
//                val resizedMax = resizedArray.maxOrNull() ?: 0f
//                Log.d("FloatArrayRange_After", "Min: $resizedMin, Max: $resizedMax")
//
//                resizedArray
//            } else {
//                Log.d("ImageResize", "No resizing needed.")
//                floatArray
//
//            }
//
//        } catch (e: Exception) {
//            Log.e("convertRawToFloatArray-", "Exception in convertRawToFloatArrayFast", e)
//            return null
//        }
//
//    }
//
//    fun runInferenceOnRaw(inputArray: FloatArray, interpreter: Interpreter, dngCreator: DngCreator) {
//        try {
//
//            //Get input tensor info
//            val inputTensor = interpreter.getInputTensor(0)
//            val inputShape = inputTensor.shape()
//            Log.d("InputTensorShape", "Input shape: ${inputShape.joinToString(",")}")
//
//            val inputBufferProvided = inputArray.take(20).joinToString(", ")
//            Log.d("Provided Input toModel", "First 20 values of inputArray: [$inputBufferProvided]")
//            Log.d("InputTensorShape", "Shape of inputArray to model: ${inputShape.joinToString(",")}")
//
//            // Get the output tensor and its shape
//            val outputTensor = interpreter.getOutputTensor(0)
//            val outputShape = outputTensor.shape()
//            val expectedOutputSize = outputShape.reduce { acc, dim -> acc * dim }
//            Log.d("OutputTensorShape", "Expected output shape: ${outputShape.joinToString(" x ")}")
//            Log.d("OutputTensorSize", "Expected total elements: $expectedOutputSize")
//
//            // Create an appropriately sized output array
//            val outputArray = Array(1) { FloatArray(expectedOutputSize) }
//            Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")
//
//            // Run inference
//            interpreter.run(arrayOf(inputArray), outputArray)
//            Log.d("Inference", "Inference completed successfully.")
//
//            // Flatten the output array (1D FloatArray)
//            val outputArray1D: FloatArray = if (outputArray.isNotEmpty()) {
//                val innerArray = outputArray[0]
//                FloatArray(innerArray.size) { i -> innerArray[i] }
//            } else {
//                FloatArray(0)
//            }
//            Log.d("OutputArray1D", "Flattened output array size: ${outputArray1D.size}")
//
//            // Log a subset of the values to verify contents (first 100 values or less)
//            val logValues = outputArray1D.take(100).joinToString(", ")
//            Log.d("OutputArray1DValues", "First 100 values: [$logValues]")
//
//            // Log min and max values
//            val minVal = outputArray1D.minOrNull() ?: 0f
//            val maxVal = outputArray1D.maxOrNull() ?: 1f
//            Log.d("OutputCheck", "Min value: $minVal, Max value: $maxVal")
//
//            // Save the processed output as a DNG file
////            val dngCreator = DngCreator(characteristics, result.metadata)
//            saveRawProcessedOutput(outputArray1D, outputShape, dngCreator)
//
//        } catch (e: Exception) {
//            Log.e("ModelError", "Error during inference: ${e.message}")
//        }
//    }
//
//    private fun saveRawProcessedOutput(
//
//
//        floatArray: FloatArray,  // Model output (normalized 0.0 - 1.0)
//        dimensions: IntArray,     // Expected [1, C, H, W] (C=1 or 4)
////
//        dngCreator: DngCreator
//    ) {
//        try {
//            val channels = dimensions[1]  // Number of channels (1 for RAW, 4 for RGBA)
//            val height = dimensions[2]    // Image height
//            val width = dimensions[3]     // Image width
//
//            Log.d("DNG-Save", "Saving DNG with size: $width x $height, Channels: $channels")
//
//            // Convert FloatArray to 16-bit RAW ByteArray
//            val byteBuffer = ByteBuffer.allocate(floatArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
//            for (value in floatArray) {
//                val scaledValue = (value * 65535).toInt().coerceIn(0, 65535)  // Normalize 0.0 - 1.0 to 0 - 65535
//                byteBuffer.putShort(scaledValue.toShort())
//            }
//
//            // Create ImageReader for RAW storage
//            val format = android.graphics.ImageFormat.RAW_SENSOR
//            val imageReader = ImageReader.newInstance(width, height, format, 1)
//
//            // Acquire an image to store the processed data
//            val image = imageReader.acquireNextImage()
//            image?.planes?.get(0)?.buffer?.put(byteBuffer.array())
//
//            // Create a DNG file in the app's private storage
////            val dngFile = File(File("/data/data/com.yourapp/files/"), "processed_output.dng")
//
//            // Save using DngCreatorval resolver = context.contentResolver
//            val context = requireContext()
//            val resolver = context?.contentResolver
//            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
//            val fileName = "IMG_Raw_Processed_${sdf.format(Date())}.dng"
//
//            if (resolver != null) {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                    // ✅ Android 10+ (API 29+): Use MediaStore API
//                    val contentValues = ContentValues().apply {
//                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
//                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
//                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Camera2Basic/Images/")
//                    }
//
//                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
//                    uri?.let {
//                        resolver.openOutputStream(it)?.use { outputStream ->
////                            val dngCreator = DngCreator(characteristics, result.metadata)
//                            dngCreator.writeImage(outputStream, image)
//                            Log.d("DNG-Save", "DNG file saved at: $uri")
//                        }
//                    } ?: Log.e("DNG-Save", "Failed to create DNG file in Downloads folder")
//
//                } else {
//                    // ✅ Android 9 and Below (API 28-21): Use File API
//                    val dngFile = File(
//                        Environment.(Environment.DIRECTORY_DOWNLOADS),
//                        "Camera2Basic/Images/$fileName")
//
//                    dngFile.parentFile?.mkdirs() // Ensure the directory exists
//
//                    FileOutputStream(dngFile).use { outputStream ->
//                        val dngCreator = DngCreator(characteristics, result.metadata)
//                        dngCreator.writeImage(outputStream, image)
//                    }
//
//                    Log.d("DNG-Save", "DNG file saved at: ${dngFile.absolutePath}")
//                }
//
//
//}