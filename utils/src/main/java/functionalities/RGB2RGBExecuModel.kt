package functionalities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import functionalities.Raw2RawModelPipeline.checkModelExecution
import functionalities.SaveUtils.saveBitmapToFile
//import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

object RGB2RGBExecuModel {

    fun runInferenceOnBitmap(bitmap: Bitmap, modelExecu: Module, filePath: String, fileName: String,width: Int, height: Int) {
        try {
            val fileNameProcessed = "${fileName}_Processed.jpeg"
            val inputArray = preprocessBitmapToModelInput(bitmap)
//            Log.d("runInferenceOnBitmap", inputArray.take(20).joinToString(", "))

            val inputShape = longArrayOf(1, 3, height.toLong(), width.toLong()) // NCHW
            Log.d("runInferenceOnBitmap", "Input shape: ${inputShape.joinToString(", ")}")

            val inputTensor = Tensor.fromBlob(inputArray, inputShape)
            val inputEValue = EValue.from(inputTensor)

            val startTime = System.nanoTime()
            val outputEValue = modelExecu.forward(inputEValue)
            val endTime = System.nanoTime()

            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
            Log.d("ExecuTorch-Inference", "Inference Time: $inferenceTimeMs ms")

            // Extract output tensor
            val outputTensor = outputEValue[0].toTensor()

            val outputData = outputTensor.dataAsUnsignedByteArray.asUByteArray()


            val outputShape = outputTensor.shape() // [1, 3, H, W]
            val outputShapeInt = outputShape.map { it.toInt() }.toIntArray()

            Log.d("OutputShape", "Output shape as IntArray: ${outputShapeInt.joinToString(", ")}")

            val channels = outputShapeInt[1]
            val imageHeight = outputShapeInt[2]
            val imageWidth = outputShapeInt[3]
            val outputSize = channels * imageHeight * imageWidth


            // Convert to UByteArray

//            val finalUByteArray = UByteArray(outputSize)
            Log.d("runInferenceOnBitmap", "First 20 Output values : ${outputData.take(20).joinToString(", ")}")
            Log.d("runInferenceOnBitmap", "OutputData size: ${outputData.size}")
//            saveProcessedRGB2RGBOutput(outputData, outputShapeInt, fileName, filePath)
//            val floatArray = FloatArray(outputData.size) { i ->
//                outputData[i].toFloat()  // Assuming normalized between 0 and 1


            // Log output shape details
            Log.d("BitmapInfo", "Output shape: [C=${outputShapeInt[1]}, H=${outputShapeInt[2]}, W=${outputShapeInt[3]}]")


            val expectedSize = imageHeight * imageWidth * 3 // RGB
            val actualSize = outputData.size

// Log computed dimensions and expected memory
            val estimatedMemoryBytes = imageHeight * imageWidth * 4 // ARGB_8888 = 4 bytes/pixel
            val estimatedMemoryMB = estimatedMemoryBytes / 1024 / 1024

            Log.d("BitmapInfo", "Creating bitmap of size: ${imageWidth}x${imageHeight}")
            Log.d("BitmapInfo", "Expected data size: $expectedSize, Actual: $actualSize")
            Log.d("BitmapInfo", "Estimated bitmap memory: ${estimatedMemoryMB}MB")

            if (actualSize < expectedSize) {
                Log.e("BitmapError", "Insufficient output data! Aborting bitmap creation.")
                return // or return from the function if not in coroutine
            }

// Proceed only if data size is valid
            val bitmapOut = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)

            var index = 0
            for (y in 0 until imageHeight) {
                for (x in 0 until imageWidth) {
                    val r = outputData[index++].toInt()
                    val g = outputData[index++].toInt()
                    val b = outputData[index++].toInt()

                    val color = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    bitmapOut.setPixel(x, y, color)
                }
            }

// Log pixel range preview
            val pixelSample = (0 until 10).map { i -> outputData[i].toUByte().toInt() }.joinToString(", ")
            Log.d("ModelProcessOutput", "First 10 pixel channel values: $pixelSample")
            Log.d("ModelProcessOutput", "Bitmap successfully created!")

            val outputFile = File(filePath, fileNameProcessed)


            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmapOut.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)

            val byteArray = byteArrayOutputStream.toByteArray()
            val chunkSize = 1024 * 1024
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
            Log.e("ExecuTorchError", "Error during inference: ${e.message}")
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
        Log.d("preprocessBitmapToModel", "Minumum Pixel value: ${minPixelValue}; Maximum Pixel value: ${maxPixelValue}")

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
        Log.d("preprocessBitmapToModel", "Preprocessed float array created.")
        Log.d("preprocessBitmapToModel", "Total values: ${floatArray.size}, Min: $minPixelValue, Max: $maxPixelValue")

        // Log a sample of the normalized pixel values
        Log.d("preprocessBitmapToModel", "Sample values: ${floatArray.take(10)}")
        bitmap.recycle()


        return floatArray
    }

    fun saveProcessedRGB2RGBOutput(outputArrayValue: UByteArray, outputShape: IntArray, fileParentName: String, filePath: String) {

        val fileNameProcessed = "${fileParentName}_Processed.jpeg"
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
//        val floatArray = FloatArray(outputArrayValue.size) { i ->
//            outputArrayValue[i].toFloat() / 255.0f // Assuming normalized between 0 and 1
//        }
        val floatArray = FloatArray(outputArrayValue.size) { i ->
            (outputArrayValue[i].toInt() and 0xFF) / 255.0f
        }
//        val floatArray = outputArrayValue

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

        val outputFile = File(filePath, fileNameProcessed)


        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)

        val byteArray = byteArrayOutputStream.toByteArray()
        val chunkSize = 1024 * 1024
        var offset = 0

        FileOutputStream(outputFile).use { outputStream ->
            while (offset < byteArray.size) {
                val chunkEnd = minOf(offset + chunkSize, byteArray.size)
                outputStream.write(byteArray.sliceArray(offset until chunkEnd))
                offset = chunkEnd
                Log.d("SavingProgress", "Saved $offset / ${byteArray.size} bytes")
            }
        }
        Log.d("ModelProcessOutput ----", "Pixel value range in bitmap: Min=$minValue, Max=$maxValue")

//        saveBitmapToFile(bitmap, fileParentName, filePath)



    }


}