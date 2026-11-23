// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bionicwookiee.g7timing.g7.ApplicatorCodeParser
import java.util.concurrent.Executors
import zxingcpp.BarcodeReader

/**
 * Full-screen camera that reads the 2D code (GS1 Data Matrix) on a G7 sensor's applicator or box.
 * Finishes with [EXTRA_CODE] set to the code's text once it reads one holding a serial number;
 * other codes are ignored. Finishes with [EXTRA_ERROR] if the camera can't be used.
 */
class ScanApplicatorActivity : ComponentActivity() {
    private val analysis = Executors.newSingleThreadExecutor()
    private lateinit var hint: TextView
    @Volatile private var done = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWith(EXTRA_ERROR, "Camera permission is needed to scan the applicator or box.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        hint = TextView(this).apply {
            text = "Point the camera at the square code on the sensor's applicator or box"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 24, 32, 24)
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(cancel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 16 })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            panel.setPadding(32, 24, 32, 24 + bars.bottom)
            insets
        }
        setContentView(root)
        this.preview = preview

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private lateinit var preview: PreviewView

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                finishWith(EXTRA_ERROR, "The camera couldn't be opened.")
                return@addListener
            }
            val reader = BarcodeReader(
                BarcodeReader.Options(
                    formats = setOf(BarcodeReader.Format.DATA_MATRIX, BarcodeReader.Format.QR_CODE),
                    tryRotate = true,
                    tryInvert = true,
                    // Keep the GS1 field separators so the serial number's end is unambiguous.
                    textMode = BarcodeReader.TextMode.PLAIN,
                ),
            )
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysis) { image ->
                image.use {
                    if (done) return@use
                    val texts = try {
                        reader.read(it).mapNotNull { r -> r.text }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val code = texts.firstOrNull { t -> ApplicatorCodeParser.parse(t) != null }
                    when {
                        code != null -> runOnUiThread { finishWith(EXTRA_CODE, code) }
                        texts.isNotEmpty() -> runOnUiThread { hint.text = "That code isn't from a G7 applicator or box. Try the square code." }
                    }
                }
            }
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageAnalysis)
            } catch (e: Exception) {
                finishWith(EXTRA_ERROR, "The camera couldn't be started.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWith(key: String, value: String) {
        if (done && key == EXTRA_CODE) return
        done = true
        setResult(RESULT_OK, Intent().putExtra(key, value))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_ERROR = "error"
    }
}
