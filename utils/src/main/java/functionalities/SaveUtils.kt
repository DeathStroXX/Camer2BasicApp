package functionalities

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.media.Image
import android.hardware.camera2.DngCreator
import android.util.Size
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object SaveUtils {

    // Save JPEG to Gallery which is converted in app from raw to jpeg
    fun saveJpegToGallery(context: Context, bitmap: Bitmap?, fileName: String) {
        val fileName1 = "${fileName}.jpeg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName1)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Camera2Basic/Images"
            )
        }

        val resolver = context.contentResolver
        val jpegUri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

        jpegUri?.let { uri ->
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            Log.d("SaveJPEG", "JPEG saved to: $uri")
        } ?: Log.e("SaveJPEG", "Failed to save JPEG")
    }
    // Save JPEG to Gallery which is converted in app from raw to jpeg
    fun saveBitmapAsJpeg(bitmap: Bitmap, dngFile: File): File? {
        try {
            // Define JPEG file name (Same as DNG, but with .jpg extension)
            val jpegFileName = "${dngFile.nameWithoutExtension}.jpg"

            // Save JPEG in the **same directory as DNG**
            val jpegFile = File(dngFile.parentFile, jpegFileName)

            // Save as JPEG
            FileOutputStream(jpegFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            Log.d("SaveJPEG", "JPEG saved at: ${jpegFile.absolutePath}")

            return jpegFile  // Explicit return statement

        } catch (e: Exception) {
            Log.e("SaveJPEG", "Error saving JPEG file", e)
            return null  // Explicit return in case of failure
        }
    }


    //    // Save DNG to Gallery with Metadata

    fun saveDngAndMetaToGallery(
        context: Context,
        dngCreator: DngCreator,
        dngFile: File,
        image: Image,
        result: CaptureResult
    ) {

        val fileName = "${dngFile.nameWithoutExtension}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Camera2Basic/Images"
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                dngCreator.writeImage(outputStream, image)
            }
            Log.d("SaveDNG", "DNG saved to: $uri")
        } ?: Log.e("SaveDNG", "Failed to save DNG")

        // ** Extract Metadata from CaptureResult**
        val metadataMap = mutableMapOf<String, Any?>()
        for (key in result.keys) {
            metadataMap[key.name] = result.get(key)
        }

        // ** Convert Metadata to JSON**
        val metadataJson = JSONObject(metadataMap).toString(4) // Pretty print JSON

        // ** Save Metadata as a JSON File**
        val metaFileName = "$fileName.json"
        val metaContentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, metaFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Camera2Basic/Images"
            )
        }

        val metaUri = resolver.insert(MediaStore.Files.getContentUri("external"), metaContentValues)

        metaUri?.let { uri ->
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(metadataJson.toByteArray())
            }
            Log.d("SaveMetadata", "Metadata saved to: $uri")
        } ?: Log.e("SaveMetadata", "Failed to save metadata")
    }

    //    Saves the given Bitmap of prcessesd image RGB2RGB model as a JPEG file.*/
    fun saveBitmapToFile(bitmap: Bitmap, fileParentName: String, filePath: String) {
        try {
            val fileNameProcessed = "${fileParentName}_Processed.jpeg"
            val file = File(filePath, fileNameProcessed)

            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }

            Log.d("ModelProcessOutput", "Processed image saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e("ModelProcessOutput", "Failed to save processed image: ${e.message}", e)
        }


    }
    // Saving the processes image as a DNG file from raw 2 raw model
     fun saveProcessedDngFile(
        dngCreator: DngCreator,
        processedBuffer: ByteBuffer,
        filePathParent: String,
        fileNameParent: String,
        width: Int,
        height: Int
    ) {
        try {
            // Create a DNG file in the app's private storage
            val fileName = "${fileNameParent}_Processed.dng"
            val dngFile = File(filePathParent, fileName)
            Log.d("DNG-Save", "DNG file will be saved at: ${dngFile.absolutePath}")

            // Reset buffer position before writing
            processedBuffer.rewind()

            // Save the processed buffer to the DNG file
            Log.d("DNG-Save", "Writing processed buffer to DNG file...")
            FileOutputStream(dngFile).use { outputStream ->
                dngCreator.writeByteBuffer(outputStream, Size(width, height), processedBuffer, 0)
            }
            Log.d("DNG-Save", "DNG file saved successfully at: ${dngFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("DNG-Save", "Error writing DNG file: ${e.message}")
            e.printStackTrace()
        }
    }
    fun saveRgbIspProcessedOutput(outputDataByteArray: ByteArray, newOutputShape: IntArray, dngCreator: DngCreator, filePath: String, fileNameParent: String){
        val outputData = outputDataByteArray.asUByteArray()
        val fileNameProcessed = "${fileNameParent}_ISP_Processed.jpeg"

        val channels = newOutputShape[1]
        val imageHeight = newOutputShape[2]
        val imageWidth = newOutputShape[3]

        // Log output shape details
        Log.d("saveRgbIspProcessedOutput", "Output shape: [C=${channels}, H=${imageHeight}, W=${imageWidth}]")


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




    }
}