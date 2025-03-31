package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.example.android.camera2.basic.R
import java.io.File

class SelectorFragment : Fragment() {

    private var rawModelPath: Uri? = null
    private var rgbModelPath: Uri? = null
    private var selectedCameraId: String? = null
    private var selectedCameraFormat: Int? = null

    private lateinit var checkBoxRaw: CheckBox
    private lateinit var checkBoxRgb: CheckBox
    private lateinit var btnSelectRaw: Button
    private lateinit var btnSelectRgb: Button
    private lateinit var btnSaveAndContinue: Button
    private lateinit var radioGroupCameras: RadioGroup

    private val selectRawModelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            rawModelPath = getFilePathFromUri(requireContext(), it)?.let { path -> Uri.parse(path) }
        }
    }

    private val selectRgbModelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            rgbModelPath = getFilePathFromUri(requireContext(), it)?.let { path -> Uri.parse(path) }
        }
    }

    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex != -1) {
                it.moveToFirst()
                val fileName = it.getString(columnIndex)
                val file = File(context.cacheDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return file.absolutePath
            }
        }
        return null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_selector, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkBoxRaw = view.findViewById(R.id.checkBoxEnableRaw)
        checkBoxRgb = view.findViewById(R.id.checkBoxEnableRgb)
        btnSelectRaw = view.findViewById(R.id.btnSelectRawModel)
        btnSelectRgb = view.findViewById(R.id.btnSelectRgbModel)
        btnSaveAndContinue = view.findViewById(R.id.btnSaveAndContinue)
        radioGroupCameras = view.findViewById(R.id.radioGroupCameras)

        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = enumerateCameras(cameraManager)

        // Populate the RadioGroup with cameras
        cameraList.forEach { item ->
            val radioButton = RadioButton(requireContext())
            radioButton.text = item.title
            radioButton.setTextColor(Color.WHITE)
            radioButton.tag = Pair(item.cameraId, item.format)
            radioGroupCameras.addView(radioButton)
        }

        checkBoxRaw.setOnCheckedChangeListener { _, isChecked ->
            btnSelectRaw.isEnabled = isChecked
        }

        checkBoxRgb.setOnCheckedChangeListener { _, isChecked ->
            btnSelectRgb.isEnabled = isChecked
        }

        btnSelectRaw.setOnClickListener {
            selectRawModelLauncher.launch("application/octet-stream")
        }

        btnSelectRgb.setOnClickListener {
            selectRgbModelLauncher.launch("application/octet-stream")
        }

        btnSaveAndContinue.setOnClickListener {
            val selectedRadioButton = radioGroupCameras.findViewById<RadioButton>(radioGroupCameras.checkedRadioButtonId)
            val selectedCameraData = selectedRadioButton?.tag as? Pair<String, Int>

            if (selectedCameraData == null) {
                Toast.makeText(requireContext(), "Please select a camera", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            selectedCameraId = selectedCameraData.first
            selectedCameraFormat = selectedCameraData.second

            if (checkBoxRaw.isChecked && rawModelPath == null) {
                Toast.makeText(requireContext(), "Please select a Raw Model", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (checkBoxRgb.isChecked && rgbModelPath == null) {
                Toast.makeText(requireContext(), "Please select an RGB Model", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val action = SelectorFragmentDirections.actionSelectorToCamera(
                selectedCameraId!!,
                selectedCameraFormat!!,
                rawModelPath?.toString() ?: "",
                rgbModelPath?.toString() ?: ""
            )
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(action)
        }
    }

    companion object {
        private data class FormatItem(val title: String, val cameraId: String, val format: Int)

        private fun enumerateCameras(cameraManager: CameraManager): List<FormatItem> {
            val availableCameras = mutableListOf<FormatItem>()

            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@forEach
                val orientation = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "External"
                }

                val outputFormats = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )?.outputFormats ?: return@forEach

                // Add JPEG format (Always available)
                availableCameras.add(FormatItem("$orientation JPEG", id, ImageFormat.JPEG))

                // Add RAW format if supported
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: intArrayOf()
                if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                    outputFormats.contains(ImageFormat.RAW_SENSOR)) {
                    availableCameras.add(FormatItem("$orientation RAW", id, ImageFormat.RAW_SENSOR))
                }
            }

            return availableCameras
        }
    }
}
