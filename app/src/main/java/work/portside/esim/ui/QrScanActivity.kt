package work.portside.esim.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import work.portside.esim.R
import java.util.concurrent.Executors

/**
 * Minimal QR scanner built on CameraX + ZXing core (no Play Services needed, so the app
 * stays self-contained on any phone). Returns the decoded text in [EXTRA_RESULT].
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "qr_result"
        private const val CAMERA_REQUEST = 1
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    @Volatile
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_qr_scan)

        // The camera preview may run edge-to-edge, but the hint and the close button
        // must stay clear of the status bar and the navigation buttons.
        val root = findViewById<FrameLayout>(R.id.scan_root)
        val hint = findViewById<TextView>(R.id.scan_hint)
        val close = findViewById<Button>(R.id.btn_close)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val density = resources.displayMetrics.density
            (hint.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + (24 * density).toInt()
            }
            hint.requestLayout()
            (close.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + (32 * density).toInt()
            }
            close.requestLayout()
            insets
        }

        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is needed to scan the QR code", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = findViewById<PreviewView>(R.id.preview).surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start the camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (handled) return
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, proxy.height,
                0, 0, proxy.width, proxy.height, false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                val result = reader.decodeWithState(bitmap)
                handled = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result.text))
                    finish()
                }
            } catch (_: NotFoundException) {
                // no code in this frame
            } finally {
                reader.reset()
            }
        } catch (_: Throwable) {
            // skip broken frames
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
