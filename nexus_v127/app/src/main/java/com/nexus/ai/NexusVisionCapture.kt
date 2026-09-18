package com.nexus.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

class NexusVisionCapture(
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val executor: Executor
) {
    private var capture: ImageCapture? = null

    fun start(onReady: () -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            capture = ImageCapture.Builder().setTargetRotation(previewView.display.rotation).build()
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
            onReady()
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    fun takePhoto(onBitmap: (Bitmap) -> Unit, onError: (String) -> Unit) {
        val imageCapture = capture ?: return onError("Câmera ainda não está pronta.")
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    var bitmap = BitmapFactory.decodeByteArray(
                        image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } },
                        0,
                        image.planes[0].buffer.remaining()
                    ) ?: error("Não foi possível decodificar a imagem.")

                    val degrees = image.imageInfo.rotationDegrees
                    if (degrees != 0) {
                        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    }
                    onBitmap(bitmap)
                } catch (e: Exception) {
                    onError(e.message ?: "Erro ao capturar.")
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Falha na captura.")
            }
        })
    }
}
