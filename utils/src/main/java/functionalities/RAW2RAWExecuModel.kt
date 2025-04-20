package functionalities

import android.hardware.camera2.DngCreator
import android.media.Image
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import org.tensorflow.lite.Delegate
//import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

object RAW2RAWExecuModel {



    fun runInferenceOnRaw(rawImg: Image, modelExecu: Module, dngCreator: DngCreator, filePath: String, fileName: String) {
        try {
            // Get input tensor info
            val inputArray = convertRawToFloatArrayFast(rawImg)
            val inputTensor = Tensor.fromBlob(
                inputArray,
                longArrayOf(1, 1, rawImg.height.toLong(), rawImg.width.toLong())
            )
            val inputEValue = EValue.from(inputTensor)

            val inputShape = inputTensor.shape()
            Log.d("runInferenceOnRaw", "Input tensor shape: ${inputShape.joinToString(",")}")
            val inBatichSize = inputShape[0]
            val inChannels = inputShape[1]
            val inHeight = inputShape[2]
            val inWidth = inputShape[3]

            val inputBufferProvided = inputArray?.take(20)?.joinToString(", ")
            Log.d("runInferenceOnRaw", "First 20 values of inputArray: [$inputBufferProvided]")
            Log.d("runInferenceOnRaw", "Shape of inputArray to model: ${inputShape.joinToString(",")}")

// Run inference using ExecuTorch
            val startTime = System.nanoTime()
            val outputs = modelExecu.forward(inputEValue)
            val endTime = System.nanoTime()

            Log.d("Inference", "Inference completed successfully.")
            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
            Log.d("ExecuTorch-Inference", "Inference Time: $inferenceTimeMs ms")

// Extract output tensor
            val outputTensor = outputs[0].toTensor()
            val outputShape = outputTensor.shape()
            Log.d("runInferenceOnRaw", "Output tensor shape: ${outputShape.joinToString(" x ")}")

            val outputChannels = outputShape[1].toInt()
            val height = outputShape[2].toInt()
            val width = outputShape[3].toInt()
            val outputSize = width * height * outputChannels

            val outputData: ByteArray = outputTensor.dataAsUnsignedByteArray
            Log.d("runInferenceOnRaw", "Flattened output array 1D size: ${outputData.size}")
            val logValues = outputData.take(20).joinToString(", ")
            Log.d("runInferenceOnRaw", "First 20 values of outputArray1D: [$logValues]")

            val minVal = outputData.minOrNull() ?: 0f
            val maxVal = outputData.maxOrNull() ?: 1f
            Log.d("OutputCheck", "Min value: $minVal, Max value: $maxVal")

            val newOutputShape = intArrayOf(1, outputChannels, height, width)
            saveRawProcessedOutput(outputData, newOutputShape, dngCreator, filePath, fileName)

//            saveRawProcessedOutput(outputArray1D, newOutputShape, dngCreator, filePath,fileName)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }

    private fun saveRawProcessedOutput(
        byteArray: ByteArray,         // Model output in Byte format (0-255)
        dimensions: IntArray,         // Expected [1, C, H, W] (C=1 or 4)
        dngCreator: DngCreator,
        filePathParent: String,
        fileNameParent: String,
    ) {
        try {
            val channels = dimensions[1]  // Number of channels (1 for RAW, 4 for RGBA)
            val height = dimensions[2]    // Image height
            val width = dimensions[3]     // Image width

            Log.d("DNG-Save", "Initializing DNG Save Process with ByteArray")
            Log.d("DNG-Save", "Image Dimensions: Width=$width, Height=$height, Channels=$channels")

            // Convert ByteArray (0-255) to 16-bit buffer (0-65535) for DNG
            Log.d("DNG-Save", "Converting byte array to 16-bit RAW buffer...")
            val byteBuffer = ByteBuffer.allocate(byteArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (value in byteArray) {
                val normalizedValue = (value.toInt() and 0xFF) // Ensure unsigned
                val scaledValue = (normalizedValue / 255.0 * 65535.0).toInt().coerceIn(0, 65535)
                byteBuffer.putShort(scaledValue.toShort())
            }

            val processedBuffer = byteBuffer
            Log.d("DNG-Save", "ByteArray conversion completed. Buffer size: ${processedBuffer.capacity()} bytes")

            // Save to DNG
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


            val floatArray = FloatArray(shortArray.size)
            // Find the min and max values of the shortArray
            val minValConvert = shortArray.minOrNull() ?: 0
            val maxValConvert = shortArray.maxOrNull() ?: 1

// Normalize using the min and max values (similar to (raw_np - raw_np.min()) / (raw_np.max() - raw_np.min()))
            for (i in shortArray.indices) {
                floatArray[i] = (shortArray[i].toFloat() - minValConvert) / (maxValConvert - minValConvert)
            }


            // Convert to FloatArray & Normalize (0-65535 → 0.0-1.0)
//            val floatArray = FloatArray(shortArray.size)
//            for (i in shortArray.indices) {
//                floatArray[i] = shortArray[i].toFloat() / 65535f
//            }


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
        val inputTensor: org.tensorflow.lite.Tensor? = interpreter.getInputTensor(0)
        val tensorDataType = when (inputTensor?.dataType()) {
            org.tensorflow.lite.DataType.FLOAT32 -> "FP32 (Float32)"
//            org.tensorflow.lite.DataType.FLOAT16 -> "FP16 (Float16)"
            else -> "Unknown Data Type"
        }

        // Log the results
        Log.d("TFLite-Execution", "Running on: $executionType")
        Log.d("TFLite-Precision", "Precision: $tensorDataType")
    }
}