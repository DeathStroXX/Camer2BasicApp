package functionalities
import android.content.Context
import android.util.Log
import org.pytorch.executorch.Module
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


object ExecuTorch {
    private lateinit var interpreter: Module
    private lateinit var interpreter2: Module

    fun loadModel(filePath: String, isRaw: Boolean): Module? {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e("Model--", "Model file does not exist at path: $filePath")
                return null
            }

            val modelFile = file.readBytes()
            val buffer = ByteBuffer.allocateDirect(modelFile.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelFile)
            }

            if (isRaw) {
                interpreter = Module.load(file.absolutePath)
//                interpreter = Interpreter(buffer)
                Log.d("Model---", "Interpreter initialized for RAW successfully from $filePath")
                return interpreter
            } else {
                // Create GPU delegate
//                val gpuDelegate = GpuDelegate()
//                val options = Interpreter.Options().apply {
//                    addDelegate(gpuDelegate) // Enable GPU acceleration
//                }
                Log.d("ExecuTorchModel", "Loading model from: ${file.absolutePath}")
                interpreter2 = Module.load(file.absolutePath)
                Log.d("ExecuTorchModel", "Model loaded successfully")

//                 Initialize interpreter with GPU delegate
//                interpreter2 = Interpreter(buffer, options)
//                interpreter2 = Interpreter(buffer)

                Log.d("Model---", "Interpreter initialized with GPU successfully.")
                Log.d("Model---", "Interpreter initialized for RGB successfully from $filePath")

                return interpreter2
            }
        } catch (e: Exception) {
            Log.e("Model--", "Failed to initialize interpreter: ${e.message}")
            return null
        }
    }

    fun loadExceuTorchModelDirectlyFromAssets(context: Context, assetFileName: String): Module? {
        return try {
            Log.d("ExecuTorch", "Loading model directly from assets: $assetFileName")

            // Read model file into a ByteArray
            val assetManager = context.assets
            val inputStream = assetManager.open(assetFileName)
            val modelBytes = inputStream.readBytes()
            inputStream.close()

            // Load model from the byte array
            val module = Module.load(modelBytes.toString())
            Log.d("ExecuTorch", "Model loaded successfully from assets")

            module
        } catch (e: Exception) {
            Log.e("ExecuTorch", "Error loading model: ${e.message}")
            null
        }
    }

//    fun loadExceuTorchModel(requireContext: Context, s: String): Module {
//
//        // Copy the .pte model from assets to internal storage if not already present
//        val modelFile = File(context.filesDir, assetFileName)
//
//        if (!modelFile.exists()) {
//            try {
//                Log.d("ExecuTorchModel", "Copying $assetFileName to internal storage...")
//                context.assets.open(assetFileName).use { inputStream ->
//                    FileOutputStream(modelFile).use { outputStream ->
//                        inputStream.copyTo(outputStream)
//                    }
//                }
//                Log.d("ExecuTorchModel", "Model copied successfully to: ${modelFile.absolutePath}")
//            } catch (e: Exception) {
//                Log.e("ExecuTorchModel", "Error copying model: ${e.message}")
//                throw e
//            }
//        } else {
//            Log.d("ExecuTorchModel", "Model already exists at: ${modelFile.absolutePath}")
//        }
//
//        return try {
//            Log.d("ExecuTorchModel", "Loading model from: ${modelFile.absolutePath}")
//            val module = Module.load(modelFile.absolutePath)
//            Log.d("ExecuTorchModel", "Model loaded successfully")
//            module
//        } catch (e: Exception) {
//            Log.e("ExecuTorchModel", "Error loading model: ${e.message}")
//            throw e
//        }
//
//    }


    fun loadExceuTorchModel1(context: Context, assetFileName: String): Module {
        // Copy the .pte model from assets to internal storage if not already present
        val modelFile = File(context.filesDir, assetFileName)

        if (!modelFile.exists()) {
            try {
                Log.d("ExecuTorchModel", "Copying $assetFileName to internal storage...")
                context.assets.open(assetFileName).use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d("ExecuTorchModel", "Model copied successfully to: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("ExecuTorchModel", "Error copying model: ${e.message}")
                throw e
            }
        } else {
            Log.d("ExecuTorchModel", "Model already exists at: ${modelFile.absolutePath}")
        }

        return try {
            Log.d("ExecuTorchModel", "Loading model from: ${modelFile.absolutePath}")
            val module = Module.load(modelFile.absolutePath)
            Log.d("ExecuTorchModel", "Model loaded successfully")
            module
        } catch (e: Exception) {
            Log.e("ExecuTorchModel", "Error loading model: ${e.message}")
            throw e
        }
    }
}