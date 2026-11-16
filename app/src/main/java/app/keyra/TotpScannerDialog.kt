package app.keyra

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Camera frames are decoded locally and are never saved. Permission is requested by the editor. */
class TotpScannerDialog(context: Context, private val scanned: (String) -> Boolean) : AlertDialog(context) {
    private val scanner = DecoratedBarcodeView(context)
    private var accepted = false

    init {
        setTitle(R.string.totp_label)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            addView(TextView(context).apply {
                setText(R.string.totp_scan_prompt)
                setPadding(dp(24), dp(12), dp(24), dp(16))
            })
            addView(scanner, LinearLayout.LayoutParams(-1, dp(320)))
        }
        setView(body)
        setButton(BUTTON_NEGATIVE, context.getString(android.R.string.cancel)) { _, _ -> dismiss() }
        scanner.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        scanner.setStatusText("")
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                scanner.setStatusText(context.getString(R.string.totp_camera_unavailable))
            }
        })
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                handleResult(result.text)
            }
        })
    }

    private fun handleResult(text: String) {
        if (!isShowing || accepted) return
        accepted = scanned(text)
        if (accepted) dismiss()
        else scanner.setStatusText(context.getString(R.string.totp_scan_invalid))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        scanner.resume()
    }

    override fun onStop() {
        scanner.pause()
        super.onStop()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
