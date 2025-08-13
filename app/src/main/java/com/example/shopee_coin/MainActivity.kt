package com.example.shopee_coin

// 新增 import

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.example.shopee_coin.ui.theme.Shopee_coinTheme
import java.util.Calendar
import kotlin.math.min

//var isOn: Boolean = false

class MainActivity<ClaimRecord> : ComponentActivity() {

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>  // 懸浮視窗的權限
    private lateinit var coinStorage: CoinClaimStorage
    private var floatingService: FloatingButtonService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as FloatingButtonService.LocalBinder
            floatingService = localBinder.getService()
            if (!MediaProjectionHolder.hasPermission()) {
                floatingService?.updateStatusText("請點開始偵測")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            floatingService = null
        }
    }

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

        gTotalHeight = realHeight.toFloat()
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
                    LimitFontScale(maxScale = 1.2f) {
                        Column(modifier = Modifier.padding(innerPadding)) {
                            Greeting(
                                name = "蝦霸-蝦幣工具",
                                modifier = Modifier
                                .scale(0.8f)
                            )

                        }
                        SetPageItems()
                    }
                }// end of Scaffold
            } // end of Shopee_coinTheme
        }  // end of setContent

    }

    @SuppressLint("DefaultLocale")
    @Composable
    fun SetPageItems() {

        var isLowEndDevice by remember { mutableStateOf(GlobalValueHolder.IsLowEndDevice) }
        var isTimeLimit by remember { mutableStateOf(GlobalValueHolder.IsTimeLimit) }
        var advanceSetting by remember { mutableStateOf(false) }

        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val componentWidth = screenWidth * 0.6f  // 元件寬度 = 螢幕的 85%

        var text2 by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(top = 35.dp, start = 1.dp)
            .graphicsLayer(scaleX = 0.86f, scaleY = 0.8f)
        ) {

            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()      // 線的寬度（可改成固定寬度）
                    .padding(vertical = 8.dp), // 上下間距
                thickness = 2.dp,        // 線的粗細
                color = Color.Gray     // 線的顏色
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp, end = 8.dp),  // 整個 Row 的頂部和右邊距離
                verticalAlignment = Alignment.CenterVertically
            ){
                Button(
                    onClick = { startShopCoinService() },
                    shape = RoundedCornerShape(12.dp),      // 圓角
                    //border = BorderStroke(5.dp, Color.LightGray), // 外框顏色
                    modifier = Modifier
                        .width(componentWidth)
                        .height(55.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Red,
                                    Color(0xFFFFA500),
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Blue,
                                    Color(0xFF4B0082),
                                    Color(0xFFEE82EE)
                                )
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(5.dp) // 外框厚度
                ) {
                    Text("按此開始 蝦幣偵測",
                        fontSize = 17.sp // 字體大小
                    )
                }

                // 間隔
                Spacer(modifier = Modifier.width(8.dp))

                CloseAppButton()
            }


            Spacer(modifier = Modifier.height(12.dp))
            Text("tips:按蝦幣偵測後 \"懸浮按鈕\"開到ON 才會做動",
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
                )

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
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = advanceSetting,
                    onCheckedChange = { checked ->
                        advanceSetting = checked
                        if (!checked) {
                            // 進階選項取消勾選時，重置內部狀態
                            text2 = ""
                            isLowEndDevice = false
                            GlobalValueHolder.IsLowEndDevice = false
                        }
                    },
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = "進階選項")
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

            if (advanceSetting) {
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
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(text = "Low-End Device(低階裝置用,變慢)",
                        fontSize = 12.sp // 自訂字體大小
                    )
                }

            }


            if (advanceSetting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp) // 元件之間間距
                ) {
                    TextField(
                        value = text2,
                        onValueChange = { newValue ->
                            // 只允許數字與最多一個小數點
                            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                text2 = newValue
                            }
                        },
                        label = {
                            Text(
                                "自訂底線值",
                                fontSize = 11.sp // 標籤字體大小
                            )
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(160.dp)
                    )
                    Button(
                        onClick = { SetDownValue(text2.toFloatOrNull() ?: 0f) }
                    ) {
                        Text("自訂底線值",
                            fontSize = 11.sp // 自訂字體大小
                        )
                    }
                    Text("自訂值：$text2",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    @Composable
    fun CloseAppButton() {
        val context = LocalContext.current

        Button(onClick = {
            val activity = context as? Activity

            // 如果服務正在執行，先停止
            if (ScreenCaptureService.isRunning) {
                val svcIntent = Intent(context, ScreenCaptureService::class.java)
                context.stopService(svcIntent)
            }

            // 關閉所有 Activity
            activity?.finishAffinity()

            // 如果確定要殺進程（不建議）
            // kotlin.system.exitProcess(0)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.DarkGray,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(5.dp),      // 圓角
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp),
            modifier = Modifier.padding(start = 5.dp)
        ) {
            Text(
                "關閉 App",
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                "⛔", // 停止符號 Emoji
                fontSize = 14.sp
            )
        }
    }

    @Composable
    fun LimitFontScale(maxScale: Float = 1.2f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, min(density.fontScale, maxScale))
        ) {
            content()
        }
    }

    @Composable
    fun CoinStatsScreen(storage: CoinClaimStorage) {
        // —— 今日統計狀態 —— //
        var todayCount by remember { mutableStateOf(0) }
        var todayAverage by remember { mutableStateOf(0.0) }
        var averageInterval by remember { mutableStateOf(0L) }
        var todayTotal by remember { mutableStateOf(0.0) }

        // —— 過去七天（不含今日） —— //
        var pastSevenDaily by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
        var pastSevenTotal by remember { mutableStateOf(0.0) }

        var showDialog by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // 取出資料：顯式型別，確保是 List<CoinClaim>
                    val allClaims: List<CoinClaim> = storage.getClaims()

                    // 今天 00:00
                    val base = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val todayStart = base.timeInMillis

                    // 7 天前 00:00（不影響 todayStart）
                    val periodStart = (base.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_MONTH, -7)
                    }.timeInMillis

                    // 僅保留最後 8 天（今天 + 前 7 天），刪除更早資料
                    val recentClaims: List<CoinClaim> =
                        allClaims.filter { claim: CoinClaim -> claim.timestamp >= periodStart }
                    storage.saveClaims(recentClaims)

                    // —— 今日統計 —— //
                    val todayClaims: List<CoinClaim> =
                        recentClaims.filter { claim: CoinClaim -> claim.timestamp >= todayStart }
                    todayCount = todayClaims.size
                    todayTotal = todayClaims.sumOf { it.amount }
                    todayAverage = if (todayClaims.isNotEmpty())
                        todayTotal / todayClaims.size
                    else 0.0

                    // 平均間距（需依時間排序）
                    averageInterval = if (todayClaims.size >= 2) {
                        val sorted = todayClaims.sortedBy { it.timestamp }
                        val intervals = sorted.zipWithNext { a, b -> b.timestamp - a.timestamp }
                        intervals.sum() / intervals.size
                    } else 0L

                    // —— 過去七天（不含今日）每日總合 —— //
                    val fmt = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
                    val pastList = mutableListOf<Pair<String, Double>>()
                    var sevenSum = 0.0

                    for (i in 1..7) {
                        val dayCal = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -i) }
                        val dayStart = dayCal.timeInMillis
                        val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

                        val dayTotal = recentClaims
                            .filter { claim: CoinClaim -> claim.timestamp in dayStart until dayEnd }
                            .sumOf { it.amount }

                        val label = fmt.format(java.util.Date(dayStart)) // 08/09 這種格式（自帶補零）
                        pastList += label to dayTotal
                        sevenSum += dayTotal
                    }

                    // 昨天在上 → 到 7 天前（由近到遠）
                    pastSevenDaily = pastList
                    pastSevenTotal = sevenSum
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // —— UI —— //
        Column(modifier = Modifier.padding(12.dp)) {
            // 今日統計
            Text(
                text = "今天領取：$todayCount 次，均：${"%.2f".format(todayAverage)}，合：${"%.2f".format(todayTotal)}",
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
            )

            if (todayCount >= 2) {
                val minutes = averageInterval / 1000 / 60
                val seconds = (averageInterval / 1000) % 60
                Text(
                    text = "平均間距：${minutes}分 ${seconds}秒",
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("平均間距：--", fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 查看過去七天（不含今日）
            Button(onClick = { showDialog = true }) {
                Text("查看近七天紀錄", fontSize = 11.sp)
            }

            if (showDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("今日+過去七天 自動領取紀錄", fontSize = 14.sp) },
                    text = {
                        Column {
                            // 今日
                            Text("今日 : ${"%.2f".format(todayTotal)}",
                                fontSize = 12.sp
                            )

                            // 分割線
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                thickness = 1.dp,
                                color = Color.Gray
                            )

                            // 過去七天（不含今日）
                            if (pastSevenDaily.isEmpty()) {
                                Text("無資料")
                            } else {
                                pastSevenDaily.forEach { (label, total) ->
                                    Text("$label : ${"%.2f".format(total)}",
                                        fontSize = 12.sp
                                        )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                androidx.compose.material3.HorizontalDivider()
                                Spacer(modifier = Modifier.height(6.dp))
                                // 七天總和（不包含今日）
                                Text(
                                    "過去七天共(不含今日) : ${"%.2f".format(pastSevenTotal)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDialog = false }) { Text("關閉") }
                    }
                )
            }
        }
    }

    @Composable
    fun AccessibilityStatusScreen() {
        val context = LocalContext.current
        var text3 by remember { mutableStateOf("檢查中...") }

        val lifecycleOwner = LocalLifecycleOwner.current

        // 每次進入前景會重新檢查
        DisposableEffect(lifecycleOwner) {

            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val isRunning = MyAccessibilityService.isRunning
                    Log.d("MyAccessibilityServiceMyAccessibilityService", "isRunning: $isRunning")
                    val isEnabled = context.isAccessibilityServiceEnabled(MyAccessibilityService::class.java)
                    isEnabledAcService = isEnabled
                    text3 = when {
                        isEnabled && isRunning->
                            "無障礙服務已啟用✅"
                        isEnabled && !isRunning ->
                            "無障礙服務 異常❌ 請重新開關"
                        else ->
                            "無障礙服務 未啟用❌ 請手動打開"
                    }
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

    @SuppressLint("InternalInsetResource")
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

    private fun Context.isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val expectedComponent = ComponentName(this, serviceClass)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.split(':')?.any {
            ComponentName.unflattenFromString(it) == expectedComponent
        } == true
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun startShopCoinService() {
        //val serviceRunning = isServiceRunning(ScreenCaptureService::class.java)
        val serviceRunning = ScreenCaptureService.isRunning
        if (!MediaProjectionHolder.hasPermission()) {
            val intent = Intent(this, ScreenCapturePermissionActivity::class.java)
            screenCaptureLauncher.launch(intent)
        } else if (!serviceRunning && MediaProjectionHolder.hasPermission()) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", MediaProjectionHolder.resultCode)
                putExtra("resultData", MediaProjectionHolder.resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "偵測已在運行", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val status = result.data?.getStringExtra("status")
            if (status == "permission_granted") {
                Toast.makeText(this, "偵測開啟成功", Toast.LENGTH_SHORT).show()
                floatingService?.updateStatusText("Loading...")
            }
        } else {
            floatingService?.updateStatusText("❌請重新點擊")
            Toast.makeText(this, "錯誤:開始偵測失敗，請重新點擊", Toast.LENGTH_SHORT).show()
        }
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
        Log.d("OCR_Result", "onResume")

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

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        floatingService = null
    }

    override fun onStart() {
        super.onStart()
        Intent(this, FloatingButtonService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

}


@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "$name!",
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