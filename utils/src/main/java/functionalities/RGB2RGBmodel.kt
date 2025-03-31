package functionalities

import android.graphics.Bitmap
import android.util.Log
import functionalities.Raw2RawModelPipeline.checkModelExecution
import functionalities.SaveUtils.saveBitmapToFile
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object RGB2RGBmodel {

fun runInferenceOnBitmap(bitmap: Bitmap, interpreter: Interpreter, fileName: String, filePath: String, width: Int, height: Int) {
    try {
        // Convert the Bitmap to a FloatArray for inference
        val fileNameProcessed = "${fileName}_Processed.jpeg"
        val inputArray = preprocessBitmapToModelInput(bitmap)
        val height1 = bitmap.height
        val width2 = bitmap.width
        val channels1 = 3  // RGB

// Log the original shape
        Log.d("Preprocess", "Original Float array shape: [$height1, $width2, $channels1]")

// Convert (h, w, 3) to (1, 3, h, w)
        val reshapedArray = Array(1) { Array(3) { Array(height1) { FloatArray(width2) } } }

// Populate the reshaped array
        var index = 0
        for (c in 0 until channels1) {
            for (h in 0 until height1) {
                for (w in 0 until width2) {
                    reshapedArray[0][c][h][w] = inputArray[index++]
                }
            }
        }

// Log the new shape
        Log.d("Preprocess", "Reshaped array to shape: [1, 3, $height1, $width2]")
        Log.d("ModelInput", "Input array size: ${inputArray.size}")

        // Get the output tensor and its shape
        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() // Should be [1, 3, 3000, 4000]

//        if (outputShape.size != 4) {
//            Log.e("TensorError", "Unexpected tensor shape: ${outputShape.joinToString(" x ")}")
//            return
//        }

        outputShape[0]=1
        outputShape[1]=3
//        outputShape[2]=height
//        outputShape[3]=width

        // Extract dimensions dynamically
        val batchSize = outputShape[0] // Usually 1
        val channels = outputShape[1]  // 3 (RGB)
        val imageHeight = height  //
        val imageWidth = width   //

        Log.d("OutputTensorShape", "Extracted Shape: Batch=$batchSize, Channels=$channels, Height=$imageHeight, Width=$imageWidth")

        // Calculate the total number of pixels
        val outputSize = imageHeight * imageWidth * channels
        Log.d("OutputTensorSize", "Total pixels: $outputSize")

        // Create an output array
        val outputArray = Array(1) { ByteArray(outputSize) }
        Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")

        // Run inference
        val startTime = System.nanoTime()
        interpreter.run(arrayOf(reshapedArray), outputArray)
        val endTime = System.nanoTime()
        Log.d("Inference", "Inference completed successfully.")

        val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
        Log.d("TFLite-Inference", "Inference Time: $inferenceTimeMs ms")

        checkModelExecution(interpreter, null)

        // Flatten the 4D tensor into a UByteArray
        val finalUByteArray = UByteArray(outputSize)
        var currentIndex = 0

        // Iterate over channels, height, and width to correctly reorder the tensor output
        for (c in 0 until channels) {
            for (h in 0 until imageHeight) {
                for (w in 0 until imageWidth) {
                    // Convert to unsigned byte and store
                    finalUByteArray[currentIndex++] = outputArray[0][(c * imageHeight * imageWidth) + (h * imageWidth) + w].toUByte()
                }
            }
        }

        // Log first 100 values to verify data integrity
        val logValues = finalUByteArray.take(100).joinToString(", ")
        Log.d("OutputArray1DValues", "First 100 values: [$logValues]")

        // Save directly to file in chunks (to avoid OutOfMemoryError)
        val outputFile = File(filePath, fileNameProcessed)  // ✅ Uses provided filename
        Log.d("ImageSaving", "Saving image to ${outputFile.absolutePath}")
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)

        val byteArray = byteArrayOutputStream.toByteArray()
        val chunkSize = 1024 * 1024 // 1MB chunk size
        var offset = 0

        FileOutputStream(outputFile).use { outputStream ->
            while (offset < byteArray.size) {
                val chunkEnd = minOf(offset + chunkSize, byteArray.size)
                outputStream.write(byteArray.sliceArray(offset until chunkEnd))
                offset = chunkEnd
                Log.d("SavingProgress", "Saved $offset / ${byteArray.size} bytes")
            }
        }

        Log.d("ImageSaving", "Image saved successfully to ${outputFile.absolutePath}")

    } catch (e: Exception) {
        Log.e("ModelError", "Error during inference: ${e.message}")
    }
}

    fun preprocessBitmapToModelInput(bitmap: Bitmap): FloatArray {

        // Resize the bitmap to the model's expected input size
//        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 2304, 1728, true)
//        Log.d("Preprocess", "Bitmap resized to: ${resizedBitmap.width}x${resizedBitmap.height}")
          val width = bitmap.width
         val height = bitmap.height
        // Initialize the FloatArray for the RGB input
        val floatArray = FloatArray(height * width * 3) // For RGB input
        var index = 0

        // Debugging variables to track pixel values
        var minPixelValue = Float.MAX_VALUE
        var maxPixelValue = Float.MIN_VALUE
        Log.d("Model Input--------", "Minumum Pixel value: ${minPixelValue}; Maximum Pixel value: ${maxPixelValue}")

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
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
     fun saveProcessedRGB2RGBOutput(outputArrayValue: UByteArray, outputShape: IntArray, fileParentName: String, filePath: String) {
        // Assuming outputShape is [Batch, Channels, Height, Width]
        val batchSize = outputShape[0] // Should be 1
        val channels = outputShape[1] // RGB = 3
        val height = outputShape[2]
        val width = outputShape[3]

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

            saveBitmapToFile(bitmap, fileParentName, filePath)



    }


}