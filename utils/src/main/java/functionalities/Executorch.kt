package functionalities

import android.util.Log
import org.pytorch.executorch.Module
import java.io.File

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
            ByteBuffer.allocateDirect(modelFile.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelFile)
            }

            if (isRaw) {
                interpreter = Module.load(file.absolutePath)

                Log.d("Model---", "Interpreter initialized for RAW successfully from $filePath")
                return interpreter
            } else {

                Log.d("ExecuTorchModel", "Loading model from: ${file.absolutePath}")
                interpreter2 = Module.load(file.absolutePath)
                Log.d("ExecuTorchModel", "Model loaded successfully")

                Log.d("Model---", "Interpreter initialized with  successfully.")
                Log.d("Model---", "Interpreter initialized for  successfully from $filePath")

                return interpreter2
            }
        } catch (e: Exception) {
            Log.e("Model--", "Failed to initialize interpreter: ${e.message}")
            return null
        }
    }


}