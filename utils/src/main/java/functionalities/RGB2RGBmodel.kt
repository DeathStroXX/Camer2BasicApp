package functionalities

import android.graphics.Bitmap
import android.util.Log
import functionalities.SaveUtils.saveBitmapToFile
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream

object RGB2RGBmodel {

    fun runInferenceOnBitmap(bitmap: Bitmap, interpreter: Interpreter, fileName: String, filePath: String) {
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
            val startTime = System.nanoTime()
            interpreter.run(arrayOf(inputArray), outputArray)
            val endTime = System.nanoTime()
            Log.d("Inference", "Inference completed successfully.")

            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
            Log.d("TFLite-Inference", "Inference Time: $inferenceTimeMs ms")


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
            saveProcessedRGB2RGBOutput(outputArray1D, outputShape, fileName, filePath)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }
     fun preprocessBitmapToModelInput(bitmap: Bitmap): FloatArray {

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
     fun saveProcessedRGB2RGBOutput(outputArrayValue: UByteArray, outputShape: IntArray, fileParentName: String, filePath: String) {
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

            saveBitmapToFile(bitmap, fileParentName, filePath)



    }


}