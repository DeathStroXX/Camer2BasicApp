package functionalities

import android.hardware.camera2.DngCreator
import android.media.Image
import android.util.Log

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

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

            val newOutputShape = intArrayOf(1, outputChannels, height, width)
            //  Conditional logic based on output channels
            when (outputChannels) {
                3 -> {
                    Log.d("OutputType", "Detected RGB output")
                    SaveUtils.saveRgbIspProcessedOutput(outputTensor, newOutputShape, dngCreator, filePath, fileName)
                }
                4 -> {
                    Log.d("OutputType", "Detected RGBA output")
                    save4ChRawProcessedOutput(outputTensor, newOutputShape, dngCreator, filePath, "$fileName-rgba")
                }
                1 -> {
                    Log.d("OutputType", "Detected Grayscale output")
                    saveRawProcessedOutput(outputTensor, newOutputShape, dngCreator, filePath, fileName)
                }
                else -> {
                    Log.w("OutputType", "Unexpected number of channels: $outputChannels")

                }
            }


        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }

    private fun saveRawProcessedOutput(
        output: Tensor,         // Model output in Byte format (0-255)
        dimensions: IntArray,         // Expected [1, C, H, W] (C=1 or 4)
        dngCreator: DngCreator,
        filePathParent: String,
        fileNameParent: String,
    ) {
        try {
            val floatArray1D = output.dataAsFloatArray
            val channels = dimensions[1] // Number of channels (1 for RAW)
            val height = dimensions[2]   // Image height
            val width = dimensions[3]    // Image width
            val outputSize = width * height * channels

            // Log tensor stats for debugging
            val minVal = floatArray1D.minOrNull() ?: 0f
            val maxVal = floatArray1D.maxOrNull() ?: 1f
            Log.d("saveRawProcessedOutput", "Float value range: min=$minVal, max=$maxVal")
            Log.d("saveRawProcessedOutput", "Image Dimensions: Width=$width, Height=$height, Channels=$channels")
            Log.d("saveRawProcessedOutput", "Flattened output array size: ${floatArray1D.size}")
            Log.d("saveRawProcessedOutput", "First 20 values: [${floatArray1D.take(20).joinToString(", ")}]")

            // Verify array size matches expected dimensions
            if (floatArray1D.size != outputSize) {
                Log.e("saveRawProcessedOutput", "Size mismatch: Expected $outputSize, got ${floatArray1D.size}")
                return
            }

            // Optional: Normalize values if the range is too narrow or skewed
            val range = if (maxVal - minVal > 0f) maxVal - minVal else 1f
            val normalizedArray = if (minVal < 0f || maxVal > 1f || range < 0.1f) {
                Log.d("saveRawProcessedOutput", "Normalizing values to [0, 1]")
                FloatArray(floatArray1D.size) { i ->
                    ((floatArray1D[i] - minVal) / range).coerceIn(0f, 1f)
                }
            } else {
                floatArray1D
            }

            val upscaledShortArray = ShortArray(normalizedArray.size)
            var minScaled = Int.MAX_VALUE
            var maxScaled = Int.MIN_VALUE

            for (i in normalizedArray.indices) {
                val scaled = (normalizedArray[i] * 65535f).toInt().coerceIn(0, 65535)
                upscaledShortArray[i] = scaled.toShort()
                if (scaled < minScaled) minScaled = scaled
                if (scaled > maxScaled) maxScaled = scaled
            }

// Log value range
            Log.d("DNG-Upscale", "Scaled value range: min=$minScaled, max=$maxScaled")

// Log first 20 values
            val preview = upscaledShortArray.take(20).joinToString(", ")
            Log.d("DNG-Upscale", "First 20 upscaled short values: [$preview]")

// Prepare ByteBuffer
            val byteBuffer = ByteBuffer.allocate(upscaledShortArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            upscaledShortArray.forEach { byteBuffer.putShort(it) }

            Log.d("DNG-Upscale", "ByteBuffer filled with ${byteBuffer.capacity()} bytes.")


            // Save to DNG
            SaveUtils.saveProcessedDngFile(dngCreator, byteBuffer, filePathParent, fileNameParent, width, height)
        } catch (e: Exception) {
            Log.e("saveRawProcessedOutput", "Error saving DNG: ${e.message}")
        }
    }

    private fun convertRawToFloatArrayFast(rawImage: Image): FloatArray? {

        try {
            val width = rawImage.width
            val height = rawImage.height


            val rawBuffer: ByteBuffer = rawImage.planes[0].buffer

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

    private fun save4ChRawProcessedOutput(
        output: Tensor,              // 4-channel float model output
        dimensions: IntArray,        // [1, 4, H, W]
        dngCreator: DngCreator,
        filePathParent: String,
        fileNameParent: String,
    ) {
        try {
            val floatArray = output.dataAsFloatArray
            val channels = dimensions[1]
            val height = dimensions[2]
            val width = dimensions[3]

            if (channels != 4) {
                Log.e("save4ChRawProcessedOutput", "Expected 4 channels, got $channels")
                return
            }

            Log.d("save4ChRawProcessedOutput", "Saving RGGB RAW: Width=$width, Height=$height, Channels=$channels")

            // Separate channels: assuming CHW order (4 * H * W)
            val planeSize = width * height
            val red = FloatArray(planeSize)
            val green1 = FloatArray(planeSize)
            val green2 = FloatArray(planeSize)
            val blue = FloatArray(planeSize)

            for (i in 0 until planeSize) {
                red[i] = floatArray[i]
                green1[i] = floatArray[i + planeSize]
                green2[i] = floatArray[i + 2 * planeSize]
                blue[i] = floatArray[i + 3 * planeSize]
            }

            // Create 1-channel RGGB layout
            val rawBayer = ShortArray(planeSize)
            var minScaled = Int.MAX_VALUE
            var maxScaled = Int.MIN_VALUE

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val value = when {
                        y % 2 == 0 && x % 2 == 0 -> red[index]      // R
                        y % 2 == 0 && x % 2 == 1 -> green1[index]   // G1
                        y % 2 == 1 && x % 2 == 0 -> green2[index]   // G2
                        else -> blue[index]                         // B
                    }

                    val scaled = (value * 65535f).toInt().coerceIn(0, 65535)
                    rawBayer[index] = scaled.toShort()

                    if (scaled < minScaled) minScaled = scaled
                    if (scaled > maxScaled) maxScaled = scaled
                }
            }

            // Log some debug info
            Log.d("save4ChRawProcessedOutput", "First 20 Bayer values: ${rawBayer.take(20)}")
            Log.d("save4ChRawProcessedOutput", "Scaled value range: min=$minScaled, max=$maxScaled")

            // Create ByteBuffer from Bayer array
            val byteBuffer = ByteBuffer.allocate(rawBayer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            rawBayer.forEach { byteBuffer.putShort(it) }

            Log.d("save4ChRawProcessedOutput", "ByteBuffer filled with ${byteBuffer.capacity()} bytes.")

            // Save using DNG Creator
            SaveUtils.saveProcessedDngFile(
                dngCreator,
                byteBuffer,
                filePathParent,
                fileNameParent,
                width,
                height
            )

        } catch (e: Exception) {
            Log.e("save4ChRawProcessedOutput", "Error saving DNG: ${e.message}")
            e.printStackTrace()
        }
    }

}