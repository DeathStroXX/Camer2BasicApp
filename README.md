
# Camera2 RAW & RGB Capture + ExecuTorch Model Inference App

This Android application extends the original [Camera2Basic](https://developer.android.com/reference/android/hardware/camera2/package-summary) project to support capturing both RAW and RGB images, storing associated metadata, and running machine learning models directly on the captured images using [ExecuTorch](https://pytorch.org/executorch/).

The app is ideal for image enhancement, mobile ML experimentation, and RAW data collection tasks. It supports executing `.pte` PyTorch models on-device, enabling real-time inference on RAW or RGB images.

## Features

- **RAW & RGB Capture**  
  Captures both `.dng` (RAW) and `.jpg` (RGB) images from supported Android devices.

- **Metadata Storage**  
  Automatically stores camera metadata in a JSON file alongside captured images.

- **Model Inference Support**  
  Run image enhancement models on-device using ExecuTorch:
  - RAW-to-RAW processing
  - RAW-to-RGB ISP pipeline
  - RGB-to-RGB enhancement

- **User Configurable**  
  Select models and image formats (RAW/RGB) at runtime through a simple UI.

---

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/<your-username>/<your-repo-name>.git
cd <your-repo-name>
```

### 2. Open in Android Studio

- Open **Android Studio**.
- Choose **"Open an existing project"** and select the cloned folder.
- Let Gradle sync the project (ensure internet access).

### 3. Device Requirements

- A physical Android device (API level 21+).
- The device **must support RAW image capture** (`Camera2` RAW sensor support).
- USB debugging enabled.

### 4. Build & Run

- Connect your device via USB.
- Select the device in Android Studio.
- Click **Run ▶** to install and launch the app.

---

##  Running Models

1. Prepare a `.pte` model file using [ExecuTorch](https://pytorch.org/executorch/stable/getting-started.html).
2. Transfer the `.pte` file to your phone's internal storage.
3. In the app:
   - Select **RAW or RGB** input.
   - Choose the model file using the file picker.
   - Capture an image — the model will run inference automatically.
4. The output image is saved in the same folder with a timestamped filename.

---

##  Output Directory

Captured images and metadata are saved to:
```
/storage/emulated/0/Download/CameraApp/
```

You’ll find:
- `image_TIMESTAMP.dng` (RAW)
- `image_TIMESTAMP.jpg` (RGB or enhanced image)
- `image_TIMESTAMP.json` (metadata)

---
