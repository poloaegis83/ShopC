package com.example.shopee_coin

// 新增 import

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.example.shopee_coin.ui.theme.Shopee_coinTheme
import java.util.Calendar

//var isOn: Boolean = false

class MainActivity : ComponentActivity() {

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>  // 懸浮視窗的權限
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>      // MediaProjection的權限
    private lateinit var mediaProjectionManager: MediaProjectionManager             // MediaProjection的權限
    private var mediaProjection: MediaProjection? = null                            // MediaProjection的權限
    private lateinit var coinStorage: CoinClaimStorage



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
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
            return
        } else {
            startService(Intent(this, FloatingButtonService::class.java)) // 加這行
        }
        //
        // 懸浮視窗的權限 End
        //

        //if (!isAccessibilityServiceEnabled(MyAccessibilityService::class.java)) {
        //    Toast.makeText(this, "⚠️ 請開啟無障礙服務以啟用自動操作", Toast.LENGTH_LONG).show()
        //}

        // MediaProjection 的權限 in 另一個 activity
        //startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))

        val (realHeight, availableHeight) = getScreenHeights(this)
        Log.d("ScreenHeight", "實體高度: $realHeight, 可用高度: $availableHeight")

        val NavigationBarHeight =  getNavigationBarHeight(this)
        Log.d("NavigationBarHeight", "NavigationBarHeight: $NavigationBarHeight")

        if (NavigationBarHeight == 0) {
            gHeightOffset = (realHeight - availableHeight).toFloat()
        } else {
            gHeightOffset = (realHeight - NavigationBarHeight - availableHeight).toFloat()
        }

        coinStorage = CoinClaimStorage(this)

        enableEdgeToEdge()

        setContent {
            Shopee_coinTheme {
                Scaffold(modifier = Modifier.fillMaxSize(),
                        //containerColor = Color.White  // 白底
                ) { innerPadding ->
                    // **新增開始**

                    Column(modifier = Modifier.padding(innerPadding)) {
                        Greeting(
                            name = "蝦霸-蝦幣工具",
                            modifier = Modifier
                            .scale(0.8f)
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

    @SuppressLint("DefaultLocale")
    @Composable
    fun SetPageItems() {
        var isLowEndDevice by remember { mutableStateOf(GlobalValueHolder.IsLowEndDevice) }
        var isTimeLimit by remember { mutableStateOf(GlobalValueHolder.IsTimeLimit) }

        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val componentWidth = screenWidth * 0.6f  // 元件寬度 = 螢幕的 85%

        var text2 by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(top = 50.dp, start = 1.dp)
            .graphicsLayer(scaleX = 0.86f, scaleY = 0.8f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp) // 元件之間間距
            ) {
                TextField(
                    value = text2,
                    onValueChange = { text2 = it },
                    label = { Text("輸入底線值") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier.width(200.dp)
                )
                Button(
                    onClick = { SetDownValue(text2.toFloatOrNull() ?: 0f) }
                ) {
                    Text("設定底線值")
                }
            }
            Text("自訂底線值：$text2")
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()      // 線的寬度（可改成固定寬度）
                    .padding(vertical = 8.dp), // 上下間距
                thickness = 2.dp,        // 線的粗細
                color = Color.Gray     // 線的顏色
            )
            Button(
                onClick = { StartShopeeCoinService(this@MainActivity) },
                modifier = Modifier.width(componentWidth)
            ) {
                Text("按此開始 蝦幣 偵測")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("tips:按蝦幣偵測後 \"懸浮按鈕\"開到ON 才會做動",
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
                )

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isLowEndDevice,
                    onCheckedChange = { checked ->
                        isLowEndDevice = checked
                        GlobalValueHolder.IsLowEndDevice = checked
                    },
                    //colors = CheckboxDefaults.colors(
                    //    checkedColor = Color.Black,
                    //    uncheckedColor = Color.Black
                    //)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Low-End Device(低階裝置用，變慢)")
            }
            val initialStartTime = String.format("%02d:%02d", GlobalValueHolder.StartHour, GlobalValueHolder.StartMinute)
            val initialEndTime = String.format("%02d:%02d", GlobalValueHolder.EndHour, GlobalValueHolder.EndMinute)

            var selectedTimeStart by remember { mutableStateOf(initialStartTime) }
            var selectedTimeEnd by remember { mutableStateOf(initialEndTime) }

            val context = LocalContext.current
            val calendar = Calendar.getInstance()

            val timePickerDialogStart = remember {
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        selectedTimeStart = String.format("%02d:%02d", hourOfDay, minute)
                        GlobalValueHolder.StartHour = hourOfDay
                        GlobalValueHolder.StartMinute = minute
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                )
            }

            val timePickerDialogEnd = remember {
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        selectedTimeEnd = String.format("%02d:%02d", hourOfDay, minute)
                        GlobalValueHolder.EndHour = hourOfDay
                        GlobalValueHolder.EndMinute = minute
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isTimeLimit,
                    onCheckedChange = { checked ->
                        isTimeLimit = checked
                        GlobalValueHolder.IsTimeLimit = checked
                    },
                    //colors = CheckboxDefaults.colors(
                    //    checkedColor = Color.Black,
                    //    uncheckedColor = Color.Black
                    //)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "啟用 時段內偵測")
            }

            if (isTimeLimit) {
                Row(modifier = Modifier.padding(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Button(onClick = { timePickerDialogStart.show() }) {
                            Text("選擇開始時間")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("開始時間：$selectedTimeStart")
                    }

                    Spacer(modifier = Modifier.width(10.dp)) // 兩個欄之間的間距

                    Column(modifier = Modifier.weight(1f)) {
                        Button(onClick = { timePickerDialogEnd.show() }) {
                            Text("選擇結束時間")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("結束時間：$selectedTimeEnd")
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()      // 線的寬度（可改成固定寬度）
                    .padding(vertical = 8.dp), // 上下間距
                thickness = 2.dp,        // 線的粗細
                color = Color.Gray     // 線的顏色
            )
            CoinStatsScreen(coinStorage)
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()      // 線的寬度（可改成固定寬度）
                    .padding(vertical = 8.dp), // 上下間距
                thickness = 2.dp,        // 線的粗細
                color = Color.Gray     // 線的顏色
            )
            AccessibilityStatusScreen()
        }
    }

    @Composable
    fun CoinStatsScreen(storage: CoinClaimStorage) {
        var todayCount by remember { mutableStateOf(0) }
        var todayAverage by remember { mutableStateOf(0.0) }
        var averageInterval by remember { mutableStateOf(0L) }  // 以毫秒為單位

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val allClaims = storage.getClaims()

                    val today = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val todayClaims = allClaims.filter { it.timestamp >= today }
                        .sortedBy { it.timestamp }  // 時間排序

                    todayCount = todayClaims.size
                    todayAverage = if (todayClaims.isNotEmpty())
                        todayClaims.sumOf { it.amount } / todayClaims.size
                    else 0.0

                    // ➕ 計算平均間距（毫秒）
                    averageInterval = if (todayClaims.size >= 2) {
                        val intervals = todayClaims.zipWithNext { a, b -> b.timestamp - a.timestamp }
                        intervals.sum() / intervals.size
                    } else 0L
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // 更新統計資料的邏輯抽出成函數方便重用
        fun updateStats() {
            val allClaims = storage.getClaims()
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val todayClaims = allClaims.filter { it.timestamp >= today }

            todayCount = todayClaims.size
            todayAverage = if (todayClaims.isNotEmpty())
                todayClaims.sumOf { it.amount } / todayClaims.size
            else 0.0
        }


        Column(modifier = Modifier.padding(5.dp)) {
            Text(text = "今天自動領取：$todayCount 次, 平均領取：${"%.2f".format(todayAverage)}")
            if (todayCount >= 2) {
                val minutes = averageInterval / 1000 / 60
                val seconds = (averageInterval / 1000) % 60
                Text("平均間距：${minutes}分 ${seconds}秒")
            } else {
                Text("平均間距：--")
            }
            Spacer(modifier = Modifier.height(3.dp))
            Button(onClick = {
                storage.clearClaims()
                updateStats()  // 清除後立即更新畫面
            }) {
                Text("清除紀錄")
            }
        }
    }

    @Composable
    fun AccessibilityStatusScreen(context: Context = LocalContext.current) {
        var text3 by remember { mutableStateOf("檢查中...") }

        val lifecycleOwner = LocalLifecycleOwner.current

        // 每次進入前景會重新檢查
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val isEnabled = context.isAccessibilityServiceEnabled(MyAccessibilityService::class.java)
                    text3 = if (isEnabled) " 無障礙服務已啟用✅ " else " 無障礙服務未啟用❌ 請手動打開"
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        Text(
            text = text3,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
        )
    }

    @SuppressLint("ServiceCast")
    fun getScreenHeights(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+
            val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
            val bounds = metrics.bounds
            val realHeight = bounds.height()

            val availableHeight = Resources.getSystem().displayMetrics.heightPixels

            Pair(realHeight, availableHeight)
        } else {
            // API < 30
            val display = windowManager.defaultDisplay
            val realMetrics = DisplayMetrics()
            display.getRealMetrics(realMetrics)
            val realHeight = realMetrics.heightPixels

            val availableMetrics = DisplayMetrics()
            display.getMetrics(availableMetrics)
            val availableHeight = availableMetrics.heightPixels

            Pair(realHeight, availableHeight)
        }
    }

    fun getNavigationBarHeight(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30 以上用 WindowInsets
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())

            insets.bottom
        } else {
            // API 29 以下從資源中抓 navigation_bar_height
            val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
        }
    }

    fun Context.isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val expectedComponent = ComponentName(this, serviceClass)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.split(':')?.any {
            ComponentName.unflattenFromString(it) == expectedComponent
        } == true
    }

    private fun StartShopeeCoinService(mainActivity: MainActivity) {
        // 由此開始 錄製
        // MediaProjection 的權限 in 另一個 activity
        //startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))
        val intent = Intent(this, ScreenCapturePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
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
        GlobalValueHolder.DownValue = DownValue
        Log.d("GlobalValueHolder", "DownValue ${GlobalValueHolder.DownValue}")
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