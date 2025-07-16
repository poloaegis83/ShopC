package com.example.shopee_coin

// 新增 import

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.shopee_coin.ui.theme.Shopee_coinTheme

//var isOn: Boolean = false

class MainActivity : ComponentActivity() {

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>  // 懸浮視窗的權限
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>      // MediaProjection的權限
    private lateinit var mediaProjectionManager: MediaProjectionManager             // MediaProjection的權限
    private var mediaProjection: MediaProjection? = null                            // MediaProjection的權限

    //@RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //
        // 懸浮視窗的權限 Start
        //
        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (Settings.canDrawOverlays(this)) {
                recreate()
            } else {
                Toast.makeText(this, "請允許懸浮窗權限以使用功能", Toast.LENGTH_LONG).show()
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        } else {
            startService(Intent(this, FloatingButtonService::class.java)) // 加這行
        }
        //
        // 懸浮視窗的權限 End
        //

        // MediaProjection 的權限 in 另一個 activity
        //startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))

        enableEdgeToEdge()


        setContent {
            Shopee_coinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // **新增開始**

                    Column(modifier = Modifier.padding(innerPadding)) {
                        Greeting(
                            name = "蝦幣工具",
                            modifier = Modifier
                        )

                    }
                    SetPageItems()

                }// end of Scaffold
            } // end of Shopee_coinTheme
        }  // end of setContent


        // ✅ 啟動前景服務（mediaProjection 專用）
        //val intent = Intent(this, ScreenCaptureService::class.java)
        //startForegroundService(intent)

    }

    @Composable
    fun SetPageItems() {

        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val componentWidth = screenWidth * 0.6f  // 元件寬度 = 螢幕的 85%

        var text1 by remember { mutableStateOf("") }
        var text2 by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(top = 50.dp, start = 20.dp)) {
            TextField(
                value = text1,
                onValueChange = { text1 = it },
                label = { Text("輸入 預期值 (預設0.4)") },
                singleLine = true,
                modifier = Modifier.width(componentWidth)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("預期值：$text1")

            Spacer(modifier = Modifier.height(12.dp)) // 兩個 TextField 間隔

            TextField(
                value = text2,
                onValueChange = { text2 = it },
                label = { Text("輸入 底線值 (預設0.2)") },
                singleLine = true,
                modifier = Modifier.width(componentWidth)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("底線值：$text2")
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { SetUpValue( text1.toFloatOrNull() ?: 0f  ) },
                modifier = Modifier.width(componentWidth)
            ) {
                Text("蝦皮 設定預期值")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { SetDownValue(text2.toFloatOrNull() ?: 0f ) },
                modifier = Modifier.width(componentWidth)
            ) {
                Text("蝦皮 設定底線值")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { StartShopeeCoinService(this@MainActivity) },
                modifier = Modifier.width(componentWidth)
            ) {
                Text("按此開始 蝦幣 偵測")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("tips: 開始 蝦幣偵測後 懸浮按鈕 打開到ON 才能做動")
        }
    }

    private fun StartShopeeCoinService(mainActivity: MainActivity) {
        // 由此開始 錄製
        // MediaProjection 的權限 in 另一個 activity
        startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))
    }

    //
    // MediaProjection的截圖
    //
    private fun requestScreenCapturePermission() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun SetDownValue(DownValue:Float){
        if (DownValue <= 0.01f) {
            Toast.makeText(this, "底線值太小", Toast.LENGTH_SHORT).show()
            return
        } else if  (DownValue >= 100f) {
            Toast.makeText(this, "底線值太大", Toast.LENGTH_SHORT).show()
            return
        }
        if (DownValue > GlobalValueHolder.UpValue) {
            Toast.makeText(this, "底線值 不可大於 預期值", Toast.LENGTH_SHORT).show()
            return
        }
        //GlobalValueHolder.DownValue = DownValue
        Log.d("GlobalValueHolder", "DownValue ${GlobalValueHolder.DownValue}")
    }
    private fun SetUpValue(UpValue:Float){
        if (UpValue <= 0.01f) {
            Toast.makeText(this, "預期值太小", Toast.LENGTH_SHORT).show()
            return
        } else if  (UpValue >= 100f) {
            Toast.makeText(this, "預期值太大", Toast.LENGTH_SHORT).show()
            return
        }
        if (UpValue < GlobalValueHolder.DownValue) {
            Toast.makeText(this, "預期值 不可小於 底線值", Toast.LENGTH_SHORT).show()
            return
        }
        //GlobalValueHolder.UpValue = UpValue
        Log.d("GlobalValueHolder", "UpValue ${GlobalValueHolder.UpValue}")
    }
    //
    // MediaProjection的截圖
    //
    private lateinit var imageReader: ImageReader
    private var virtualDisplay: VirtualDisplay? = null
    // 每 10 秒呼叫這個來擷取畫面
    // **新增開始**
    private fun takeScreenshot( activity: ComponentActivity) {
        Log.d("shot", "takeScreenshot")
/*
        if (mediaProjection == null) {
            //requestScreenCapturePermission()
            Toast.makeText(this, "尚未取得螢幕擷取權限", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isOn) return
        Log.d("shot", "takeScreenshot2")

        val screenDensity = resources.displayMetrics.densityDpi
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        //val CutBitmap = cropToTopRightQuarter (bitmap)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = createBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            runOnUiThread {
                Log.d("shot", "imageReader 已成功擷取畫面 Bitmap！")
            }
        }, Handler(Looper.getMainLooper()))


        val SnapShot = createBitmap(view.width, view.height)
        val canvas = Canvas(SnapShot)
        view.draw(canvas)

        val CutBitmap = cropToTopRightQuarter (SnapShot)

        //val filename = "screenshot_${System.currentTimeMillis()}.png"
        //val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        //val file = File(directory, filename)

        /*try {
            FileOutputStream(file).use { out ->
                CutBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                Toast.makeText(activity, "截圖已儲存至 ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(activity, "截圖失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }*/

        // ML 辨識
        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = CutBitmap,
            context = this, // activity context
            onResult = { resultText ->
                // 在這裡接收到辨識的文字
                Log.d("OCR_Result", "辨識文字內容：$resultText")
                // 顯示在畫面上 Toast
                Toast.makeText(this, "辨識到：$resultText", Toast.LENGTH_LONG).show()
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
            }
        )*/
    }
    // **新增結束**


    //裁切圖片為右上角四分之一區域
    private fun cropToTopRightQuarter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val croppedWidth = width / 2
        val croppedHeight = height / 2

        val rect = Rect(croppedWidth, 0, width, croppedHeight)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }


    override fun onDestroy() {
        super.onDestroy()
        // Activity 銷毀時停止定時
        val stopIntent = Intent(this, FloatingButtonService::class.java)
        stopService(stopIntent)
        val stopIntent1 = Intent(this, ScreenCaptureService::class.java)
        stopService(stopIntent1)
    }

    override fun onResume() {
        super.onResume()
        Log.d("OCR_Result", "onResumeonResumeonResumeonResumeonResumeonResumeonResumeonResumeonResumeonResume")

        /*if (MediaProjectionHolder.resultData != null && mediaProjection == null) {
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(
                MediaProjectionHolder.resultCode,
                MediaProjectionHolder.resultData!!
            )

            Toast.makeText(this, "✅ 螢幕擷取授權成功", Toast.LENGTH_SHORT).show()
        }*/
    }

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Shopee_coinTheme {
        Greeting("Android")
    }
}