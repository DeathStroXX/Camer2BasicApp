
package functionalities

//package your.package.name.util

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

object ModelUtils {

    fun loadModelFile(context: Context, fileName: String): ByteBuffer {
        val assetManager = context.assets
//        val fileDescriptor = assetManager.openFd("new_simple_model.tflite") //Working rgb2rgb
//        val fileDescriptor = assetManager.openFd("new_model.tflite")
//        val fileDescriptor = assetManager.openFd("Rawsimple_model.tflite")
//        val fileDescriptor = assetManager.openFd("Checksimple_model.tflite")
//        val fileDescriptor = assetManager.openFd("Raw124simple_model diff.tflite")
//        val fileDescriptor = assetManager.openFd("1C3Kx4Krawsimple_model.tflite")
//        val fileDescriptor = assetManager.openFd("simple_model 17.tflite")
//        val fileDescriptor = assetManager.openFd("simple_model18.tflite")
//        val fileDescriptor = assetManager.openFd("bayer_unpack3.tflite")
//          val fileDescriptor = assetManager.openFd("simple_modelmarc.tflite") //Raw2Raw
        val fileDescriptor = assetManager.openFd("simple_model_sai_rdb.tflite") //rgb2rgb

//                val fileDescriptor = assetManager.openFd("simple_model_A.tflite") //raw2raw 4000x3000




        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        ).also {
            inputStream.close()
        }
    }

    fun createInterpreter(context: Context, modelName: String): Interpreter {
        val modelBuffer = loadModelFile(context, modelName)
        return Interpreter(modelBuffer)
    }
}
