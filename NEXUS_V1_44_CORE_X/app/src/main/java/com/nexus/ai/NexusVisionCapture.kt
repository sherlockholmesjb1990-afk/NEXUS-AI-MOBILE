package com.nexus.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/** CameraX capture kept as one lifecycle-bound controller per preview. */
class NexusVisionCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val executor: Executor
) {
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    fun start(onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                capture = ImageCapture.Builder()
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                onReady()
            } catch (e: Exception) {
                capture = null
                onError(e.message ?: "Não foi possível iniciar a câmera.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhoto(onBitmap: (Bitmap) -> Unit, onError: (String) -> Unit) {
        val imageCapture = capture ?: return onError("A câmera ainda está inicializando.")
        val outputFile = File.createTempFile("nexus-camera-", ".jpg", context.cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                        ?: error("Não foi possível decodificar a imagem capturada.")
                    onBitmap(bitmap)
                } catch (e: Exception) {
                    onError(e.message ?: "Erro ao preparar a imagem capturada.")
                } finally {
                    outputFile.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                outputFile.delete()
                onError(exception.message ?: "Falha na captura da câmera.")
            }
        })
    }

    fun stop() {
        provider?.unbindAll()
        capture = null
    }
}
