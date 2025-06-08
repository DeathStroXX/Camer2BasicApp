package functionalities

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

object RGB2RGBExecuModel {

    fun runInferenceOnBitmap(bitmap: Bitmap, modelExecu: Module, filePath: String, fileName: String,width: Int, height: Int) {
        try {
            val fileNameProcessed = "${fileName}_Processed.jpeg"
            val inputArray = preprocessBitmapToModelInput(bitmap)

            val maxHeapSize = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            Log.d("MemoryInfo", "Max heap size: ${maxHeapSize}MB")

            val inputShape = longArrayOf(1, 3, height.toLong(), width.toLong()) // NCHW
            Log.d("runInferenceOnBitmap", "Input shape: ${inputShape.joinToString(", ")}")

            val inputTensor = Tensor.fromBlob(inputArray, longArrayOf(1, 3, height.toLong(), width.toLong()))
            val inputEValue = EValue.from(inputTensor)


            val startTime = System.nanoTime()
            val outputEValue = modelExecu.forward(inputEValue)
            val endTime = System.nanoTime()

            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
            Log.d("ExecuTorch-Inference", "Inference Time: $inferenceTimeMs ms")

            val tensor = outputEValue.toList()[0].toTensor()

            val floatArray = tensor.dataAsFloatArray  // Instead of .toByteArray()


// Convert to ByteArray
fun convertFloatArrayFromNCHWToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
    val bitmap2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val channelSize = width * height
    val rOffset = 0
    val gOffset = channelSize
    val bOffset = channelSize * 2

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x

            val r = (data[rOffset + index] * 255f).coerceIn(0f, 255f).toInt()
            val g = (data[gOffset + index] * 255f).coerceIn(0f, 255f).toInt()
            val b = (data[bOffset + index] * 255f).coerceIn(0f, 255f).toInt()

            val color = Color.rgb(r, g, b)
            bitmap2.setPixel(x, y, color)
        }
    }

    return bitmap2
}

// Convert ByteArray to Bitmap
            val bitmap2 = convertFloatArrayFromNCHWToBitmap(floatArray, width, height)

            val outputFile = File(filePath, fileNameProcessed)
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap2.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)

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
        } finally {
            bitmap.recycle()
            System.gc()
        }
    }

    private fun preprocessBitmapToModelInput(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height

        val r = FloatArray(height * width)
        val g = FloatArray(height * width)
        val b = FloatArray(height * width)

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                r[index] = (pixel shr 16 and 0xFF) / 255.0f
                g[index] = (pixel shr 8 and 0xFF) / 255.0f
                b[index] = (pixel and 0xFF) / 255.0f
                index++
            }
        }

        // Combine into NCHW format: [R..., G..., B...]
        val nchw = FloatArray(3 * height * width)
        System.arraycopy(r, 0, nchw, 0, r.size)
        System.arraycopy(g, 0, nchw, r.size, g.size)
        System.arraycopy(b, 0, nchw, r.size + g.size, b.size)

        bitmap.recycle()
        return nchw
    }

}