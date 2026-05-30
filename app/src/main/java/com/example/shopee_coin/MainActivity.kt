package com.example.shopee_coin

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import com.example.shopee_coin.ui.theme.Shopee_coinTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>  // 懸浮視窗的權限
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<Array<String>> // 儲存空間權限
    private var showAccessibilityDialog by mutableStateOf(false)
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

    private fun loadSwipeSettings() {
        val prefs = getSharedPreferences("SwipeSettings", MODE_PRIVATE)
        GlobalValueHolder.nextMoveFactor = prefs.getFloat("nextMoveFactor", GlobalValueHolder.DEFAULT_NEXT_FACTOR)
        GlobalValueHolder.nextMoveLong = prefs.getLong("nextMoveLong", GlobalValueHolder.DEFAULT_NEXT_LONG)
        GlobalValueHolder.prevMoveFactor = prefs.getFloat("prevMoveFactor", GlobalValueHolder.DEFAULT_PREV_FACTOR)
        GlobalValueHolder.prevMoveLong = prefs.getLong("prevMoveLong", GlobalValueHolder.DEFAULT_PREV_LONG)
    }

    private fun saveSwipeSettings() {
        val prefs = getSharedPreferences("SwipeSettings", MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("nextMoveFactor", GlobalValueHolder.nextMoveFactor)
            putLong("nextMoveLong", GlobalValueHolder.nextMoveLong)
            putFloat("prevMoveFactor", GlobalValueHolder.prevMoveFactor)
            putLong("prevMoveLong", GlobalValueHolder.prevMoveLong)
            apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSwipeSettings()

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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
            startService(Intent(this, FloatingButtonService::class.java))
        }

        storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (!granted) {
                Log.w("MainActivity", "部分儲存權限被拒絕，ImageDebug 可能無法工作")
            }
        }
        checkAndRequestStoragePermissions()

        val isEnabled = isAccessibilityServiceEnabled(MyAccessibilityService::class.java)
        if (!isEnabled || !MyAccessibilityService.isRunning) {
            showAccessibilityDialog = true
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (realWidth, realHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            bounds.width() to bounds.height()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            displayMetrics.widthPixels to displayMetrics.heightPixels
        }

        gTotalWidth = realWidth.toFloat()
        gTotalHeight = realHeight.toFloat()
        gHeightOffset = 0f

        Log.d("ScreenSize", "實體解析度: ${realWidth}x$realHeight")

        coinStorage = CoinClaimStorage(this)

        enableEdgeToEdge()

        setContent {
            Shopee_coinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LimitFontScale(maxScale = 1.2f) {
                        Column(modifier = Modifier.padding(innerPadding)) {
                            Greeting(
                                name = "蝦霸-蝦幣工具",
                                modifier = Modifier.scale(0.8f)
                            )

                            if (showAccessibilityDialog) {
                                val isEnabledAc = isAccessibilityServiceEnabled(MyAccessibilityService::class.java)
                                
                                val dialogText = if (!isEnabledAc) {
                                    "需要「無障礙服務」權限才能執行點擊與滑動。請在設定中找到「蝦霸」並開啟服務。"
                                } else {
                                    "無障礙服務目前處於「異常狀態」（已開啟但未正常運作）。這通常是 Android 系統的問題，請到設定中將「蝦霸」服務「關掉再重新打開」即可修復。"
                                }

                                AlertDialog(
                                    onDismissRequest = { showAccessibilityDialog = false },
                                    title = { Text(if (!isEnabledAc) "需要無障礙服務權限" else "無障礙服務狀態異常") },
                                    text = { Text(dialogText) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showAccessibilityDialog = false
                                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }) {
                                            Text("前往設定")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showAccessibilityDialog = false }) {
                                            Text("取消")
                                        }
                                    }
                                )
                            }
                        }
                        SetPageItems()
                    }
                }
            }

            KeepScreenOn()
            DoubleBackToExitApp()
        }

    }

    @SuppressLint("DefaultLocale")
    @Composable
    fun SetPageItems() {

        var showSwipeTuningDialog by remember { mutableStateOf(false) }

        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val componentWidth = screenWidth * 0.6f

        var text2 by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(top = 6.dp, start = 1.dp)
            .graphicsLayer(scaleX = 0.86f, scaleY = 0.8f)
        ) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                thickness = 2.dp,
                color = Color.Gray
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ){
                Button(
                    onClick = { startShopCoinService() },
                    shape = RoundedCornerShape(12.dp),
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
                        .padding(5.dp)
                ) {
                    Text("按此開始 蝦幣偵測", fontSize = 16.sp)
                }

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
                    calendar[Calendar.HOUR_OF_DAY],
                    calendar[Calendar.MINUTE],
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
                    calendar[Calendar.HOUR_OF_DAY],
                    calendar[Calendar.MINUTE],
                    true
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ){
                    Checkbox(
                        checked = GlobalValueHolder.IsTimeLimit,
                        onCheckedChange = { checked ->
                            GlobalValueHolder.IsTimeLimit = checked
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "啟用 時段內偵測")
                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ){
                    Checkbox(
                        checked = GlobalValueHolder.advanceSetting,
                        onCheckedChange = { checked ->
                            GlobalValueHolder.advanceSetting = checked
                            if (!checked) {
                                text2 = ""
                                GlobalValueHolder.IsLowEndDevice = false
                                GlobalValueHolder.appCheckRestartFeature = false
                                GlobalValueHolder.notInTimeBcckToHere = false
                                GlobalValueHolder.isOldCompatibilityMode = false
                                GlobalValueHolder.isDebugMode = false
                            }
                        },
                    )

                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = "進階選項")
            }

            if (GlobalValueHolder.IsTimeLimit) {
                Row(modifier = Modifier.padding(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Button(onClick = { timePickerDialogStart.show() }) {
                            Text("選擇開始時間", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(7.dp))
                        Text("開始時間：$selectedTimeStart")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Button(onClick = { timePickerDialogEnd.show() }) {
                            Text("選擇結束時間", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(7.dp))
                        Text("結束時間：$selectedTimeEnd")
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                thickness = 2.dp,
                color = Color.Gray
            )
            CoinStatsScreen(coinStorage, GlobalValueHolder.advanceSetting)
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                thickness = 2.dp,
                color = Color.Gray
            )

            AccessibilityStatusScreen()

            if (GlobalValueHolder.advanceSetting) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                    ) {
                        Checkbox(
                            checked = GlobalValueHolder.IsLowEndDevice ,
                            onCheckedChange = { checked ->
                                GlobalValueHolder.IsLowEndDevice = checked
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(text = "Low-End Device(低階裝置用)", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Checkbox(
                            checked = GlobalValueHolder.appCheckRestartFeature,
                            onCheckedChange = { checked ->
                                GlobalValueHolder.appCheckRestartFeature = checked
                                if (!checked) {
                                    GlobalValueHolder.notInTimeBcckToHere = false
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(text = "自動重啟蝦皮", fontSize = 12.sp)

                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Checkbox(
                            checked = GlobalValueHolder.isOldCompatibilityMode,
                            onCheckedChange = { checked ->
                                GlobalValueHolder.isOldCompatibilityMode = checked
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(text = "舊蝦模式", fontSize = 12.sp)
                    
                    if (GlobalValueHolder.advanceSetting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center
                        ){
                            Checkbox(
                                checked = GlobalValueHolder.isDebugMode,
                                onCheckedChange = { checked ->
                                    GlobalValueHolder.isDebugMode = checked
                                    if (!checked) GlobalValueHolder.isImageDebugMode = false
                                },
                            )
                        }
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = "Debug訊息", fontSize = 12.sp)

                        if (GlobalValueHolder.isDebugMode) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Checkbox(
                                    checked = GlobalValueHolder.isImageDebugMode,
                                    onCheckedChange = { checked ->
                                        GlobalValueHolder.isImageDebugMode = checked
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(text = "ImageDebug", fontSize = 12.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ){
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Checkbox(
                            checked = GlobalValueHolder.notInTimeBcckToHere,
                            onCheckedChange = { checked ->
                                GlobalValueHolder.notInTimeBcckToHere = checked
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "排程時段外，返回此APP等", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            if (isEnabledAcService && MyAccessibilityService.isRunning) {
                                if (!isServiceRunning(ScreenCaptureService::class.java)) {
                                    val intent = Intent(this@MainActivity, ScreenCaptureService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(intent)
                                    } else {
                                        startService(intent)
                                    }
                                }
                                showSwipeTuningDialog = true 
                            } else {
                                showAccessibilityDialog = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("調整滑動", fontSize = 11.sp)
                    }
                }
            }


            if (GlobalValueHolder.advanceSetting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = text2,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                text2 = newValue
                            }
                        },
                        label = { Text("自訂底線值", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(160.dp)
                    )
                    Button(
                        onClick = { setDownValue(text2.toFloatOrNull() ?: 0f) }
                    ) {
                        Text("自訂底線值", fontSize = 11.sp)
                    }
                    Text("自訂值：$text2", fontSize = 11.sp)
                }
            }

            ImageForMe()
        }

        if (showSwipeTuningDialog) {
            SwipeTuningDialog(onDismiss = { showSwipeTuningDialog = false })
        }
    }

    @Composable
    fun KeepScreenOn() {
        val context = LocalContext.current
        val keepOn = GlobalValueHolder.notInTimeBcckToHere

        DisposableEffect(keepOn) {
            val activity = context as Activity
            if (keepOn) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Composable
    fun ImageForMe() {
        val images = listOf(
            R.drawable.money1, R.drawable.money2, R.drawable.money3, R.drawable.money4, R.drawable.money5,
            R.drawable.money6, R.drawable.money7, R.drawable.money8, R.drawable.money9, R.drawable.money10,
            R.drawable.money11, R.drawable.money12, R.drawable.money13, R.drawable.money14, R.drawable.money15,
            R.drawable.money16, R.drawable.money17, R.drawable.money18, R.drawable.money19, R.drawable.money20,
            R.drawable.money21, R.drawable.money22, R.drawable.money23, R.drawable.money24, R.drawable.money25
        )

        var remainingImages by remember { mutableStateOf(images.shuffled()) }
        var currentImage by remember { mutableIntStateOf(remainingImages.first()) }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(id = currentImage),
                contentDescription = "右下角圖片",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(190.dp)
                    .clickable {
                        remainingImages = remainingImages.drop(1)
                        if (remainingImages.isEmpty()) {
                            remainingImages = images.shuffled()
                        }
                        currentImage = remainingImages.first()
                    }
            )
        }
    }

    @Composable
    fun CloseAppButton() {
        val context = LocalContext.current

        Button(onClick = {
            val activity = context as? Activity
            if (ScreenCaptureService.isRunning) {
                val svcIntent = Intent(context, ScreenCaptureService::class.java)
                context.stopService(svcIntent)
            }
            activity?.finishAffinity()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.DarkGray,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(5.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp),
            modifier = Modifier.padding(start = 5.dp)
        ) {
            Text("關閉 App", fontSize = 12.sp)
            Spacer(modifier = Modifier.width(1.dp))
            Text("⛔", fontSize = 14.sp)
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
    fun CoinStatsScreen(storage: CoinClaimStorage, advanceSetting: Boolean) {
        var todayCount by remember { mutableIntStateOf(0) }
        var todayAverage by remember { mutableDoubleStateOf(0.0) }
        var averageInterval by remember { mutableLongStateOf(0L) }
        var todayTotal by remember { mutableDoubleStateOf(0.0) }
        var todayClaims by remember { mutableStateOf<List<CoinClaim>>(emptyList()) }

        var pastSevenDaily by remember { mutableStateOf<List<Triple<String, Double, Int>>>(emptyList()) }

        var showDialog by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val allClaims: List<CoinClaim> = storage.getClaims()

                    val base = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val todayStart = base.timeInMillis

                    val periodStart = (base.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_MONTH, -7)
                    }.timeInMillis

                    val recentClaims: List<CoinClaim> =
                        allClaims.filter { it.timestamp >= periodStart }
                    storage.saveClaims(recentClaims)

                    val today = recentClaims.filter { it.timestamp >= todayStart }
                    todayClaims = today

                    todayCount = today.size
                    todayTotal = today.sumOf { it.amount }
                    todayAverage = if (today.isNotEmpty()) todayTotal / today.size else 0.0

                    averageInterval = if (today.size >= 2) {
                        val sorted = today.sortedBy { it.timestamp }
                        val intervals = sorted.zipWithNext { a, b -> b.timestamp - a.timestamp }
                        intervals.sum() / intervals.size
                    } else 0L

                    val fmt = SimpleDateFormat("MM/dd", Locale.getDefault())
                    val pastList = mutableListOf<Triple<String, Double, Int>>()

                    for (i in 1..7) {
                        val dayCal = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -i) }
                        val dayStart = dayCal.timeInMillis
                        val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

                        val dayClaims = recentClaims.filter { it.timestamp in dayStart until dayEnd }
                        val dayTotal = dayClaims.sumOf { it.amount }
                        val dayCount = dayClaims.size.coerceAtMost(100)

                        val label = fmt.format(Date(dayStart))
                        pastList += Triple(label, dayTotal, dayCount)
                    }

                    pastSevenDaily = pastList
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(modifier = Modifier.padding(8.dp)) {
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

            Spacer(modifier = Modifier.height(3.dp))
            var showDetailDialog by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ){
                Button(onClick = { showDialog = true },
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(10.dp),
                )
                {
                    Text("查看近七天紀錄", fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { showDetailDialog = true },
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("查看今日明細", fontSize = 11.sp)
                }

            }

            TodayClaimDetailDialog(
                showDialog = showDetailDialog,
                onDismiss = { showDetailDialog = false },
                todayClaims = todayClaims
            )

            if (showDialog) {
                var confirmDelete by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("今日+過去七天 自動領取紀錄", fontSize = 14.sp) },
                    text = {
                        Column {
                            Text(
                                "今日 : ${"%.2f".format(todayTotal)}，次數：$todayCount",
                                fontSize = 12.sp
                            )

                            HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                thickness = 1.dp,
                                color = Color.Gray
                            )

                            val pastSevenTotalSum = pastSevenDaily.sumOf { it.second }
                            val pastSevenTotalCount = pastSevenDaily.sumOf { it.third }

                            if (pastSevenDaily.isEmpty()) {
                                Text("無資料")
                            } else {
                                pastSevenDaily.forEach { (label, total, count) ->
                                    Text(
                                        "$label : ${"%.2f".format(total)}， ${count}次",
                                        fontSize = 12.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "過去七天(不含今日) : ${"%.2f".format(pastSevenTotalSum)}, 總次數：$pastSevenTotalCount",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            val nonZeroDays = pastSevenDaily.filter { (_, _, count) -> count > 0 }

                            val averageAmount = if (nonZeroDays.isNotEmpty()) {
                                nonZeroDays.sumOf { (_, amount, _) -> amount } / nonZeroDays.size
                            } else 0.0

                            val averageCount = if (nonZeroDays.isNotEmpty()) {
                                nonZeroDays.sumOf { (_, _, count) -> count } / nonZeroDays.size.toDouble()
                            } else 0.0

                            Text(
                                "平均(排除0次) : ${"%.2f".format(averageAmount)}/天, ${"%.1f".format(averageCount)}/次/天",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )

                            if (confirmDelete) {
                                Text(
                                    "真的要清除全部嗎？",
                                    color = Color.Red,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDialog = false }) { Text("關閉") }
                    },
                    dismissButton = if (advanceSetting) {
                        {
                            if (!confirmDelete) {
                                TextButton(onClick = { confirmDelete = true }) {
                                    Text("清除全部")
                                }
                            } else {
                                TextButton(onClick = {
                                    todayCount = 0
                                    todayTotal = 0.0
                                    todayAverage = 0.0
                                    averageInterval = 0L

                                    pastSevenDaily = pastSevenDaily.map { (label, _, _) ->
                                        Triple(label, 0.0, 0)
                                    }

                                    storage.clearClaims()
                                    confirmDelete = false
                                }) {
                                    Text("確定刪除")
                                }
                            }
                        }
                    } else null
                )
            }
        }
    }


    @Composable
    fun TodayClaimDetailDialog(
        showDialog: Boolean,
        onDismiss: () -> Unit,
        todayClaims: List<CoinClaim>
    ) {
        if (showDialog) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("今日領取明細", fontSize = 14.sp) },
                text = {
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            Text("排序", modifier = Modifier.weight(0.2f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("蝦幣", modifier = Modifier.weight(0.3f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("時間", modifier = Modifier.weight(0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("與上一筆時差", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        HorizontalDivider()

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            val sortedClaims = todayClaims.sortedByDescending { it.timestamp }

                            itemsIndexed(sortedClaims) { index, claim ->
                                val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                    .format(Date(claim.timestamp))

                                val diffString = if (index == sortedClaims.lastIndex) {
                                    "----"
                                } else {
                                    val diffMs = sortedClaims[index].timestamp - sortedClaims[index + 1].timestamp
                                    val diffSec = diffMs / 1000
                                    val minutes = diffSec / 60
                                    val seconds = diffSec % 60
                                    "${minutes}′${seconds.toString().padStart(2, '0')}″"
                                }

                                Row(Modifier.fillMaxWidth()) {
                                    Text("${sortedClaims.size - index}", modifier = Modifier.weight(0.2f), fontSize = 12.sp)
                                    Text("%.2f".format(claim.amount), modifier = Modifier.weight(0.3f), fontSize = 12.sp)
                                    Text(timeString, modifier = Modifier.weight(0.5f), fontSize = 12.sp)
                                    Text(diffString, modifier = Modifier.weight(0.6f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
            )
        }
    }

    @Composable
    fun AccessibilityStatusScreen() {
        val context = LocalContext.current
        var text3 by remember { mutableStateOf("檢查中...") }

        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner) {

            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val isRunningAc = MyAccessibilityService.isRunning
                    val isEnabled = context.isAccessibilityServiceEnabled(MyAccessibilityService::class.java)
                    isEnabledAcService = isEnabled
                    text3 = when {
                        isEnabled && isRunningAc->
                            "無障礙服務已啟用✅"
                        isEnabled && !isRunningAc ->
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
            modifier = Modifier.padding(top = 12.dp)
                .clickable {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp, 
                fontWeight = FontWeight.Bold, 
                color = if (isEnabledAcService && MyAccessibilityService.isRunning) Color.Black else Color.Red
            )
        )
    }

    @Composable
    fun DoubleBackToExitApp() {
        var lastBackPressedTime by remember { mutableLongStateOf(0L) }
        val exitInterval = 2000L
        val context = LocalContext.current

        BackHandler {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressedTime < exitInterval) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressedTime = currentTime
                Toast.makeText(context, "再按一次返回鍵退出", Toast.LENGTH_SHORT).show()
            }
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
        if (!MyAccessibilityService.isRunning) {
            Toast.makeText(this, "❌請先 打開 或 重開 無障礙服務", Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun setDownValue(downValue:Float){
        if (downValue <= 0.01f) {
            Toast.makeText(this, "底線值太小", Toast.LENGTH_SHORT).show()
            return
        } else if  (downValue >= 100f) {
            Toast.makeText(this, "底線值太大", Toast.LENGTH_SHORT).show()
            return
        }
        GlobalValueHolder.DownValue = downValue
        Log.d("GlobalValueHolder", "DownValue ${GlobalValueHolder.DownValue}")
    }

    @Composable
    fun SwipeTuningDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = {
                onDismiss()
            },
            title = { Text("滑動參數調整") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SwipeConfigItemUI("下一個房間 (上滑)", 
                        GlobalValueHolder.nextMoveFactor, 
                        GlobalValueHolder.nextMoveLong,
                        onFactorChange = { GlobalValueHolder.nextMoveFactor = it },
                        onLongChange = { GlobalValueHolder.nextMoveLong = it }
                    )

                    SwipeConfigItemUI("上一個房間 (下滑)", 
                        GlobalValueHolder.prevMoveFactor, 
                        GlobalValueHolder.prevMoveLong,
                        onFactorChange = { GlobalValueHolder.prevMoveFactor = it },
                        onLongChange = { GlobalValueHolder.prevMoveLong = it }
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { 
                                GlobalValueHolder.isSwipeTesting = !GlobalValueHolder.isSwipeTesting 
                                if (GlobalValueHolder.isSwipeTesting) {
                                    Toast.makeText(this@MainActivity, "測試將在 3 秒後開始，請立即切換到蝦皮直播", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (GlobalValueHolder.isSwipeTesting) Color.Red else Color(0xFF4CAF50)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (GlobalValueHolder.isSwipeTesting) "停止測試" else "測試滑動", color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    saveSwipeSettings()
                    onDismiss()
                }) {
                    Text("儲存並關閉")
                }
            },
            dismissButton = {
                Button(onClick = {
                    GlobalValueHolder.nextMoveFactor = GlobalValueHolder.DEFAULT_NEXT_FACTOR
                    GlobalValueHolder.nextMoveLong = GlobalValueHolder.DEFAULT_NEXT_LONG
                    GlobalValueHolder.prevMoveFactor = GlobalValueHolder.DEFAULT_PREV_FACTOR
                    GlobalValueHolder.prevMoveLong = GlobalValueHolder.DEFAULT_PREV_LONG
                }) {
                    Text("還原預設")
                }
            }
        )
    }

    @Composable
    fun SwipeConfigItemUI(title: String, factor: Float, duration: Long, onFactorChange: (Float) -> Unit, onLongChange: (Long) -> Unit) {
        var fText by remember(factor) { mutableStateOf(factor.toString()) }
        var lText by remember(duration) { mutableStateOf(duration.toString()) }

        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = fText,
                    onValueChange = { 
                        fText = it
                        it.toFloatOrNull()?.let { factorVal -> onFactorChange(factorVal) }
                    },
                    label = { Text("距離係數", fontSize = 10.sp) },
                    modifier = Modifier.width(90.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = lText,
                    onValueChange = { 
                        lText = it
                        it.toLongOrNull()?.let { longVal -> onLongChange(longVal) }
                    },
                    label = { Text("時間(ms)", fontSize = 10.sp) },
                    modifier = Modifier.width(90.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val stopIntent = Intent(this, FloatingButtonService::class.java)
        stopService(stopIntent)
        val stopIntent1 = Intent(this, ScreenCaptureService::class.java)
        stopService(stopIntent1)
    }

    override fun onResume() {
        super.onResume()
        Log.d("OCR_Result", "onResume")
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

    private fun checkAndRequestStoragePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val neededPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            storagePermissionLauncher.launch(neededPermissions.toTypedArray())
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