package functionalities

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

object Raw2RawModelPipeline {



    fun runInferenceOnRaw(inputArray: FloatArray, interpreter: Interpreter, dngCreator: DngCreator, filePath: String, fileName: String) {
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
            val startTime = System.nanoTime()
            interpreter.run(arrayOf(inputArray), outputArray)
            val endTime = System.nanoTime()
            Log.d("Inference", "Inference completed successfully.")

            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
            Log.d("TFLite-Inference", "Inference Time: $inferenceTimeMs ms")

            checkModelExecution(interpreter, null)

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

            saveRawProcessedOutput(outputArray1D, outputShape, dngCreator, filePath,fileName)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }
    private fun saveRawProcessedOutput(
        floatArray: FloatArray,  // Model output (normalized 0.0 - 1.0)
        dimensions: IntArray,     // Expected [1, C, H, W] (C=1 or 4)
        dngCreator: DngCreator,
        filePathParent: String,
        fileNameParent: String,

    ) {
        try {
            val channels = dimensions[1]  // Number of channels (1 for RAW, 4 for RGBA)
            val height = dimensions[2]    // Image height
            val width = dimensions[3]     // Image width

            Log.d("DNG-Save", "Initializing DNG Save Process")
            Log.d("DNG-Save", "Image Dimensions: Width=$width, Height=$height, Channels=$channels")

            // Convert FloatArray to 16-bit RAW ByteArray
            Log.d("DNG-Save", "Converting float array to 16-bit RAW byte buffer...")
            val byteBuffer = ByteBuffer.allocate(floatArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (value in floatArray) {
                val scaledValue = (value * 65535).toInt().coerceIn(0, 65535)  // Normalize 0.0 - 1.0 to 0 - 65535
                byteBuffer.putShort(scaledValue.toShort())
            }
            val processedBuffer = byteBuffer
            Log.d("DNG-Save", "Float array conversion completed. Buffer size: ${processedBuffer.capacity()} bytes")

            // Create ImageReader for RAW storage
            Log.d("DNG-Save", "Creating ImageReader with format RAW_SENSOR...")
//

                SaveUtils.saveProcessedDngFile(dngCreator, processedBuffer, filePathParent, fileNameParent, width, height)


        } catch (e: Exception) {
            Log.e("DNG-Save", "Error saving DNG file: ${e.message}")
            e.printStackTrace()
        }
    }

    fun convertRawToFloatArrayFast(rawImage: Image): FloatArray? {

        try {
            if (rawImage == null) {
                Log.e("convertRawToFloat-", "rawImage is null")
                return null
            }
            val width = rawImage.width
            val height = rawImage.height


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



            // Log min and max after resizing
            val min = floatArray.minOrNull() ?: 0f
            val max = floatArray.maxOrNull() ?: 0f
            Log.d("FloatArrayRange_After", "Min: $min, Max: $max")

            return  floatArray


        } catch (e: Exception) {
            Log.e("convertRawToFloatArray-", "Exception in convertRawToFloatArrayFast", e)
            return null
        }

    }

    fun checkModelExecution(interpreter: Interpreter, delegate: Delegate?) {
        val executionType = when (delegate) {
            is GpuDelegate -> "GPU"
            is NnApiDelegate -> "NNAPI"
            else -> "CPU (Default)"
        }

        // Check Floating Point Precision (FP32 or FP16)
        val inputTensor: Tensor = interpreter.getInputTensor(0)
        val tensorDataType = when (inputTensor.dataType()) {
            org.tensorflow.lite.DataType.FLOAT32 -> "FP32 (Float32)"
//            org.tensorflow.lite.DataType.FLOAT16 -> "FP16 (Float16)"
            else -> "Unknown Data Type"
        }

        // Log the results
        Log.d("TFLite-Execution", "Running on: $executionType")
        Log.d("TFLite-Precision", "Precision: $tensorDataType")
    }
}