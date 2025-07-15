package com.example.shopee_coin

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

class ScreenCapturePermissionActivity : Activity() {


    //private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>      // MediaProjection的權限
    private lateinit var mediaProjectionManager: MediaProjectionManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("shot", "onCreate ScreenCapturePermissionActivity11111111111111111111111111111111111111111")


        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()

        Log.d("shot", "onCreate ScreenCapturePermissionActivity1333333333333333333333333333333333")

        startActivityForResult(intent, 1001)

        //finish()
    }


    //@RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Log.d("ScreenCapture", "已取得螢幕錄製權限")
            MediaProjectionHolder.resultCode = resultCode
            MediaProjectionHolder.resultData = data
            //Log.d("ScreenCapture", "onActivityResult data is null: ${data == null}, data content: ${data?.toUri(0)}") // 確認data內容
            // 將授權資料放入 Intent 傳給 Service
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }


            // 啟動前景服務（mediaProjection 專用）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Log.w("ScreenCapture", "使用者拒絕或資料為 null")
        }

        Log.d("ScreenCapture", "啟動前景服務")

        finish()
    }


}