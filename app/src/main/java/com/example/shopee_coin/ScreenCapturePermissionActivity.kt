package com.example.shopee_coin

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ScreenCapturePermissionActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 只會在 Android 11+ 使用
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePermissionResult(REQUEST_CODE_CAPTURE, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("shot", "onCreate ScreenCapturePermissionActivity")

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 用新版 API
            screenCaptureLauncher.launch(captureIntent)
        } else {
            // Android 10- 用舊版 API
            startActivityForResult(captureIntent, REQUEST_CODE_CAPTURE)
        }
    }

    // Android 10- 會走這裡
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handlePermissionResult(requestCode, resultCode, data)
    }

    private fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK && data != null) {
            Log.d("ScreenCapture", "已取得螢幕錄製權限")

            // 保存到靜態變數（避免 Intent 序列化失效）
            MediaProjectionHolder.resultCode = resultCode
            MediaProjectionHolder.resultData = data
            Log.d("MediaProjectionHolder", "MediaProjectionHolder.resultCode = ${MediaProjectionHolder.resultCode}, MediaProjectionHolder.resultData = ${MediaProjectionHolder.resultData} ")
            // 啟動前景服務
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 回傳成功狀態給呼叫方
            setResult(
                RESULT_OK,
                Intent().apply { putExtra("status", "permission_granted") }
            )
        } else {
            Log.w("ScreenCapture", "使用者拒絕或資料為 null")
            setResult(
                RESULT_CANCELED,
                Intent().apply { putExtra("error", "permission_denied") }
            )
        }

        finish()
    }

    companion object {
        private const val REQUEST_CODE_CAPTURE = 1001
    }
}