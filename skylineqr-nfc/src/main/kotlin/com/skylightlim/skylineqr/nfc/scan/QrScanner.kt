package com.skylightlim.skylineqr.nfc.scan

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR codes from the back camera, decoded by ML Kit's bundled model (offline, no Play services). */
object QrScanner {

  /**
   * Shows the camera on [surfaceProvider] and calls [onQr] once, on the main thread, with the first
   * QR code's text. Needs the CAMERA permission. Close the result to release the camera.
   */
  suspend fun start(
    context: Context,
    owner: LifecycleOwner,
    surfaceProvider: Preview.SurfaceProvider,
    onQr: (String) -> Unit,
  ): AutoCloseable {
    val provider = ProcessCameraProvider.awaitInstance(context)
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
    val scanner = BarcodeScanning.getClient(
      BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    val executor = Executors.newSingleThreadExecutor()
    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setResolutionSelector(
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          )
          .build(),
      )
      .build()
    analysis.setAnalyzer(executor, QrAnalyzer(scanner, onQr))
    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    return AutoCloseable {
      provider.unbind(preview, analysis)
      analysis.clearAnalyzer()
      scanner.close()
      executor.shutdown()
    }
  }
}

private class QrAnalyzer(private val scanner: BarcodeScanner, private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {

  private val found = AtomicBoolean(false)

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(image: ImageProxy) {
    val frame = image.image
    if (found.get() || frame == null) {
      image.close()
      return
    }
    // ML Kit calls back on the main thread.
    scanner.process(InputImage.fromMediaImage(frame, image.imageInfo.rotationDegrees))
      .addOnSuccessListener { codes ->
        val text = codes.firstNotNullOfOrNull { it.rawValue }
        if (text != null && found.compareAndSet(false, true)) onQr(text)
      }
      .addOnCompleteListener { image.close() }
  }
}
