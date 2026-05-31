package com.example.shopee_coin

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private val upscaleRate = 2f
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    val moveActionMutex = Mutex()
    var callBackInterval = 5000L
    var notFindConter = 0
    var searchCount = 0
    var fullRefreshPositionX  = 0f
    var fullRefreshPositionY  = 0f

    var isDuringRestart = false

    var getCoinFreezeCount = 0

    var coinStates = CState.COIN_START

    // 狀態丟失計計數器 (用於 WAITING_COIN 豁免)
    private var stateLossCounter = 0

    // 優化：回溯導航 (Backtracking)
    private val roomHistoryMap = mutableMapOf<Int, Pair<Float, Long>>()
    private var currentRoomIndex = 0
    private val historyTtl = 5 * 60 * 1000L // 5分鐘內有效

    var checkLiveStreamingPageRetryCount = 0
    var notInLiveStreamingPageReopenRequest = false
    var findShopeeMainPage = false

    var mainPageStreamLiveEntryX = 0f
    var mainPageStreamLiveEntryY = 0f

    enum class CState {
        COIN_START ,GET_COIN_READY, WAITING_COIN, SEARCHING_COIN, PAGE_COIN_NOT_FIND, NOT_FIND_DOING_FRESH, COIN_VAULE_FIND
    }

    var gIsCapturing = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val singleServiceScope = CoroutineScope(singleThreadDispatcher + serviceJob)

    val metrics: android.util.DisplayMetrics = Resources.getSystem().displayMetrics

    private lateinit var coinClaimStorage: CoinClaimStorage

    var waitingLiveChecker = false

    // Debug 資訊變數已移至 GlobalValueHolder

    private var floatingService: FloatingButtonService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FloatingButtonService.LocalBinder
            floatingService = binder?.getService()
            Log.d("onServiceConnected", "onServiceConnected  floatingService")
            isBound = true
            floatingService?.updateRecordTextToday()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            floatingService = null
        }
    }

    companion object {
        @Volatile var isRunning = false
            private set
        @Volatile var lastState: CState = CState.COIN_START
    }


    object CheckInLiveStreamLimiter {
        private var lastCallTime: Long = 0L  // 儲存上次成功呼叫的時間

        fun canCall(): Boolean {
            val now = System.currentTimeMillis()
            val millis = 30 * 1000  // 30 秒
            return if (now - lastCallTime >= millis) {
                lastCallTime = now
                true
            } else {
                false
            }
        }
    }

    object AppShopCheckerLimiter {
        private var lastCallTime: Long = 0L  // 儲存上次成功呼叫的時間

        fun canCall(): Boolean {
            val now = System.currentTimeMillis()
            val millis = 15 * 1000  //  2 mins

            return if (now - lastCallTime >= millis) {
                lastCallTime = now
                true
            } else {
                false
            }
        }
    }

    object RecordCionLimiter {
        private var lastCallTime: Long = 0L  // 儲存上次成功呼叫的時間

        fun canCall(): Boolean {
            val now = System.currentTimeMillis()
            val fiveMinutesMillis = 5 * 60 * 1000  // 5分鐘 = 300,000 毫秒

            return if (now - lastCallTime >= fiveMinutesMillis) {
                lastCallTime = now
                true
            } else {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        coinClaimStorage = CoinClaimStorage(this)  // this 是 context
        val intent = Intent(this, FloatingButtonService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
        
        // 💡 啟動測試滑動 Loop (不依賴螢幕錄製權限)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            startSwipeTestLoop()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startSwipeTestLoop() {
        serviceScope.launch {
            // 💡 增加一段緩衝延遲，確保 Service 與無障礙服務完全對接
            delay(1000L)
            var wasTesting = false
            while (isActive) {
                if (GlobalValueHolder.isSwipeTesting) {
                    if (!wasTesting) {
                        Log.d("SwipeTest", "測試即將開始，等待 3 秒讓使用者切換到蝦皮...")
                        delay(3000L)
                        wasTesting = true
                    }
                    try {
                        Log.d("SwipeTest", "執行測試滑動: 下一房 -> 上一房")
                        moveActionMutex.withLock {
                            moveNextPage()
                        }
                        delay(5000L)
                        // 二次檢查防止延遲期間使用者已關閉測試
                        if (GlobalValueHolder.isSwipeTesting) {
                            moveActionMutex.withLock {
                                movePreviousPage()
                            }
                        }
                        delay(5000L)
                    } catch (e: Exception) {
                        Log.e("SwipeTest", "滑動測試發生錯誤: ${e.message}")
                        GlobalValueHolder.isSwipeTesting = false
                        wasTesting = false
                    }
                } else {
                    wasTesting = false
                    delay(1000L)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?
                                , flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, createNotification())
        }

        Log.d("ScreenCapture", "MediaProjection OK")

        val resultCode = intent?.getIntExtra("resultCode", 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }

        Log.d("ScreenCaptureService", "Extracted resultCode: $resultCode, resultData is null: ${resultData == null}")

        if (resultCode == RESULT_OK && resultData != null) {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            if (!isRunning) {
                // TODO: 在這裡啟動 VirtualDisplay 或 imageReader 擷取畫面
                Log.d("ScreenCapture", "MediaProjection 已啟動")

                setupMediaProjection()
                setupVirtualDisplay()
                serviceScope.launch {
                    searchLogic(true)
                }
                startCaptureLoopNew()
                isRunning = true
            }
        } else {
            Log.e("ScreenCapture", "MediaProjection 權限無效")
        }

        return START_STICKY
    }

    private fun setupMediaProjection() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d("MediaProjection", "MediaProjection stopped")
                updateFloatButtonText("❌:請重按 偵測蝦幣")
                cleanup()  // 釋放資源
                stopSelf()
            }
        }, handler)
    }

    private fun setupVirtualDisplay() {
        // 使用螢幕真實實體尺寸，確保 1:1 像素映射
        val width = gTotalWidth.toInt().takeIf { it > 0 } ?: metrics.widthPixels
        val height = gTotalHeight.toInt().takeIf { it > 0 } ?: metrics.heightPixels
        val density = metrics.densityDpi

        Log.d("ScreenCaptureService", "建立 1:1 虛擬顯示: ${width}x${height}")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private var captureJob: Job? = null
    private var isActive = true
    private var isInExecuteTime = true
    private var counter = 0

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startCaptureLoopNew() {
        captureJob = singleServiceScope.launch {
            while (isActive) {
                if (counter%10 == 0) {
                    isInExecuteTime = isNowInTimeRangeCheck()
                }
                if(isInExecuteTime){
                    captureScreenFrame()
                    intervalModifier()
                    delay(callBackInterval)
                } else {
                    if (GlobalValueHolder.isOn) {
                        // 不在執行時間，睡個較長時間減輕 CPU 負擔
                        updateFloatButtonText("⏸\uFE0F未在排程時段中")
                        if (!GlobalValueHolder.notInTimeBcckToHere){
                            delay(6000L) // 6秒，根據需求調整
                        } else {
                            delay(3500L)
                            updateFloatButtonText("待時段內將自動開蝦皮")
                            if (!MyAccessibilityService.checkForegroundMyApp() && counter%3 == 0){
                                openMyApp()
                            }
                            delay(3500L)
                        }
                    } else {
                        updateFloatButtonText("⏸\uFE0F暫停時段 & 按鈕關閉")
                    }
                }
                counter = (counter + 1) % 10
            }
        }
    }


    fun openMyApp() {
        serviceScope.launch {
            Log.d("openMyApp", "openMyApp")
            // this@ScreenCaptureService 是 Context
            val context: Context = this@ScreenCaptureService
            val intent = context.packageManager.getLaunchIntentForPackage("com.meteor.alderlake")
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Service 中啟動 Activity 必須加
            if (intent != null) {
                context.startActivity(intent)
            }
        }
    }
    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
        imageReader?.setOnImageAvailableListener(null, null) // 移除 listener
    }

    fun findCurrentStrategy(hour: Int, mins: Int, strategies: List<CoinStrategy>): CoinStrategy? {
        val nowTotalMinutes = hour * 60 + mins

        return strategies.find { strategy ->
            val startMinutes = strategy.startHour * 60 + strategy.startMins
            val endMinutes = strategy.endHour * 60 + strategy.endMins
            nowTotalMinutes in startMinutes until endMinutes
        }
    }

    fun updateFloatButtonText(text: String) {
        if (isBound) {
            floatingService?.updateStatusText(text)
        } else {
            Log.w("ScreenCaptureService", "FloatingButtonService 尚未綁定")
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun reopenShopeeApp(context: Context, mode: Int) {
        val pm = context.packageManager
        var launchIntent = pm.getLaunchIntentForPackage("com.shopee.tw")
        Log.e("reopenShopeeApp", "launchIntent1")
        if (launchIntent == null) {
            // fallback: 查找 Main + Launcher Activity
            Log.e("reopenShopeeApp", "launchIntent2 == null")
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = "com.shopee.tw"
            }
            val resolveList = pm.queryIntentActivities(intent, 0)
            if (resolveList.isNotEmpty()) {
                val activityInfo = resolveList[0].activityInfo
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(activityInfo.packageName, activityInfo.name)
                }
                Log.e("reopenShopeeApp", "launchIntent2 == null")
            }
        }
        //Log.e("Shopee", "找不到可啟動的 Activity")
        if (launchIntent != null) {
            if(mode == 1){
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else if (mode == 2) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            context.startActivity(launchIntent)
        } else {
            Log.e("Shopee", "找不到可啟動的 Activity")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun searchLogic(isInit: Boolean) {
        // ...
        val (tHour, tMins) = TimeLib.GetTime()
        var upValueNow = 0f
        var downValueNow = 0f
        var minusValueNow = 0f
        var refreshCountNow = 10
        val currentTStrategy = findCurrentStrategy(tHour, tMins, DefaultStrategies)

        if (currentTStrategy != null) {
            Log.d("CoinStrategy", "目前時段策略：$currentTStrategy")
            upValueNow = currentTStrategy.periodUpValue
            downValueNow = currentTStrategy.periodDownValue
            minusValueNow = currentTStrategy.coinValueMinus
            refreshCountNow = currentTStrategy.refreshCount
            
            // 更新 Debug 資訊
            GlobalValueHolder.debugUpValue = upValueNow
            GlobalValueHolder.debugDownValue = downValueNow
            GlobalValueHolder.debugPeriodInfo = String.format(java.util.Locale.US, "%02d:%02d-%02d:%02d", 
                currentTStrategy.startHour, currentTStrategy.startMins, 
                currentTStrategy.endHour, currentTStrategy.endMins)
        } else {
            Log.d("CoinStrategy", "目前沒有適用的策略")
            upValueNow = 0.3f
            downValueNow = 0.2f
            minusValueNow = 0.1f
            refreshCountNow = 5
            GlobalValueHolder.debugUpValue = upValueNow
            GlobalValueHolder.debugDownValue = downValueNow
            GlobalValueHolder.debugPeriodInfo = "No Strategy"
        }
        if (GlobalValueHolder.DownValue != 0f) {
            //
            // Override by User input
            //
            downValueNow = GlobalValueHolder.DownValue
        }
        if (isInit) {
            GlobalValueHolder.coinValueSatisfy = upValueNow
        } else {
            if (coinStates == CState.NOT_FIND_DOING_FRESH) {
                Log.d("SearchLogic", "CState.PAGE_COIN_NOT_FIND searchCount += 1")
                searchCount += 1
            } else if (coinStates == CState.WAITING_COIN || coinStates == CState.GET_COIN_READY) {
                searchCount = 0
            }
            if (coinStates == CState.WAITING_COIN) {
                Log.d("SearchLogic", "CState.WAITING_COIN")
            }

            Log.d("SearchLogic", "searchCount = $searchCount, CoinValueSatisfy = ${GlobalValueHolder.coinValueSatisfy}")
            if (coinStates  == CState.NOT_FIND_DOING_FRESH) {
                if (searchCount > refreshCountNow) {
                    val nextSatisfy = maxOf(downValueNow, GlobalValueHolder.coinValueSatisfy - minusValueNow)
                    
                    // 嘗試回溯：在歷史紀錄中尋找符合 nextSatisfy 的房間
                    val now = System.currentTimeMillis()
                    val bestPastRoom = roomHistoryMap.filter { 
                        it.value.second > now - historyTtl && it.value.first >= nextSatisfy 
                    }.maxByOrNull { it.value.first }

                    if (bestPastRoom != null && bestPastRoom.key < currentRoomIndex) {
                        val targetIndex = bestPastRoom.key
                        val stepsBack = currentRoomIndex - targetIndex
                        Log.d("SearchLogic", "找到歷史優質房間: Index $targetIndex, Value ${bestPastRoom.value.first}, 回滑 $stepsBack 次")
                        
                        GlobalValueHolder.coinValueSatisfy = nextSatisfy
                        searchCount = 0
                        
                        moveActionMutex.withLock {
                            repeat(stepsBack) {
                                movePreviousPage()
                                delay(5500L) // 等待動畫穩定
                            }
                        }
                    } else {
                        // 無歷史可用，執行原始的全域刷新邏輯
                        searchCount = 0
                        GlobalValueHolder.coinValueSatisfy = nextSatisfy
                        Log.d("SearchLogic", "無合適歷史，執行 FullFreshPage")
                        fullFreshPage()
                    }
                }
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun appCheckRestart(){
        if (!AppShopCheckerLimiter.canCall()){
            return
        }
        if(GlobalValueHolder.appCheckRestartFeature && MyAccessibilityService.isRunning && !isDuringRestart) {
            var driveSuccess = false
            try {
                if(!MyAccessibilityService.checkForegroundApp()) {
                    isDuringRestart = true
                    MyAccessibilityService.performHome()
                    delay(5000L)

                    updateFloatButtonText("嘗試重啟蝦皮，勿動做")
                    reopenShopeeApp(this,1)
                    Log.d("appCheckRestart", "reopenShopeeApp")
                    updateFloatButtonText("重啟蝦皮，勿動螢幕")
                    delay(15000L)

                    driveSuccess = backToMainAndDriveToStream()

                    if (driveSuccess) {
                        liveStreamPageCorrection()
                    }
                    lastState = coinStates

                } else if ( notInLiveStreamingPageReopenRequest) {
                    isDuringRestart = true
                    notInLiveStreamingPageReopenRequest = false
                    driveSuccess = backToMainAndDriveToStream()
                    if (driveSuccess) {
                        liveStreamPageCorrection()
                    }
                    lastState = coinStates
                }
            } finally {
                isDuringRestart = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun liveStreamPageCorrection ()
    {
        delay(14000L)
        moveLeftPage()
        delay(8000L)
        moveLeftPage()
        delay(8000L)
        moveRightPage()
        //delay(5000L)
        //FindNextRoom()
        delay(2000L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun backToMainAndDriveToStream(): Boolean {
        updateFloatButtonText("嘗試導回直播頁面")
        findShopeeMainPage = false
        var retry = 0
        while (!findShopeeMainPage) {
            if (retry > 10) {
              break
            }
            platformBackGesture()
            checkShopeeMainPage()
            delay(6000L)
            retry += 1
        }
        if (findShopeeMainPage) {
            delay(5000L)
            mainPageStreamLiveEntryX = metrics.widthPixels / 2f
            touchClick (mainPageStreamLiveEntryX,mainPageStreamLiveEntryY - 12f)
            delay(3000L)
            return true
        } else {
            return false
        }

    }


    private fun intervalModifier () {

        if (!MyAccessibilityService.isRunning) {
            Log.e("updateFloatButtonText", "錯誤:請重開無障礙服務")
            updateFloatButtonText("❌:請打開(重開)無障礙服務")
            callBackInterval = 6000L
            return
        }

        if (!GlobalValueHolder.isOn) {
            updateFloatButtonText("已暫停 點擊打開")
            Log.d("OCR_Line", "Feature Close")
            return
        }

        if (coinStates == CState.WAITING_COIN) {
            callBackInterval = (13000L..15000L).random()
            if(GlobalValueHolder.IsLowEndDevice){
                callBackInterval = (callBackInterval.toFloat() * 1.51f).toLong()
            }
            Log.d("callBackInterval", " Long time (Waiting Coin) ")
            updateFloatButtonText("等待蝦幣完成")
        } else {
            callBackInterval = (5700L..6200L).random()
            if(GlobalValueHolder.IsLowEndDevice){
                callBackInterval = (callBackInterval.toFloat() * 2f).toLong()
            }
            Log.d("callBackInterval", " Short Time")
            updateFloatButtonText("尋找蝦幣中")
        }
        Log.d("callBackInterval", "Normal Mode → Interval: $callBackInterval ms")
    }

    private fun startAndCheckSkip():Boolean {

        if (isDuringRestart) {
            return true
        }

        if (gIsCapturing) {
            Log.d("gIsCapturing", "gIsCapturing yes")
            return true
        } else {
            Log.d("gIsCapturing", "gIsCapturing no")
        }

        if (GlobalValueHolder.isOn) {
            Log.d("OCR_Line", "Feature Open")
        } else {
            Log.d("OCR_Line", "Feature Close")
            return true
        }

        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun captureScreenFrame() {
        Log.d("coinStates", "$coinStates")
        if (startAndCheckSkip()) {
            //imageReader?.acquireLatestImage()?.close()
            gIsCapturing = false
            Log.d("captureScreenFrame", "SKIP")
            return
        }

        appCheckRestart()

        searchLogic(false)

        Log.d("captureScreenFrame", "GO")
        gIsCapturing = true
        
        // 重置 Debug 資訊
        // 💡 只有在狀態切換回 SEARCHING_COIN 時才重置資訊，
        // 這樣可以讓抓到的 CoinVal 在畫面上留久一點
        if (coinStates == CState.SEARCHING_COIN) {
            GlobalValueHolder.debugCoinPosValue = "Non"
            GlobalValueHolder.debugGetPos = "Non"
            GlobalValueHolder.debugLineText = ""
            GlobalValueHolder.debugLineVal = ""
        }

        Log.d("acquireLatestImage", "acquireLatestImage Start")
        val image = moveActionMutex.withLock {
            imageReader?.acquireLatestImage()
        }
        if (image == null) {
            gIsCapturing = false
            return
        }
        Log.d("acquireLatestImage", "acquireLatestImage End")

        TimeLib.GetTime()
        if (coinStates == CState.NOT_FIND_DOING_FRESH){
            delay(500L)
        }

        var bitmap: Bitmap? = null
        try {
            bitmap = getBitmapFromImage(image)
            checkInLiveStreamingPage(bitmap)
            handleEventCase(bitmap)
            handleCoinCase(bitmap)
        } catch (e: Exception) {
            Log.e("processImage", "Error: ${e.message}")
        } finally {
            bitmap?.recycle()  // 安全回收
            image.close()      // 永遠記得關掉 Image buffer
            
            // 更新 Debug 訊息
            if (GlobalValueHolder.isDebugMode) {
                var debugMsg = "Goal:${String.format(java.util.Locale.US, "%.1f", GlobalValueHolder.coinValueSatisfy)} " +
                               "(Up:${String.format(java.util.Locale.US, "%.1f", GlobalValueHolder.debugUpValue)} " +
                               "Dn:${String.format(java.util.Locale.US, "%.1f", GlobalValueHolder.debugDownValue)})\n" +
                               "Peri(${GlobalValueHolder.debugPeriodInfo})\n" +
                               "CoinVal:${GlobalValueHolder.debugCoinPosValue}\n" +
                               "GetBtn:${GlobalValueHolder.debugGetPos}\n" +
                               "Intv:$callBackInterval ms\n" +
                               "Sta:$coinStates"
                if (GlobalValueHolder.debugLineText.isNotEmpty()) {
                    debugMsg += "\nTxt:${GlobalValueHolder.debugLineText}"
                }
                if (GlobalValueHolder.debugLineVal.isNotEmpty()) {
                    debugMsg += "\nTime:${GlobalValueHolder.debugLineVal}"
                }
                floatingService?.updateDebugInfo(debugMsg)
            }
        }

        gIsCapturing = false
    }

    private fun getBitmapFromImage( imageIn: Image):Bitmap{
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels

        Log.d("displayMetrics.width  Pixels", "width =: $width , height =: $height")
        val planes = imageIn.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        Log.d("rowPadding", "rowPadding =: $rowPadding , pixelStride =: $pixelStride, rowPadding / pixelStride = ${rowPadding / pixelStride} ")

        val bitmapWithPadding = createBitmap(imageIn.width + rowPadding / pixelStride, imageIn.height, Bitmap.Config.ARGB_8888)
        bitmapWithPadding.copyPixelsFromBuffer(buffer)
        val finalBitmap = createBitmap(bitmapWithPadding, 0, 0, imageIn.width, imageIn.height)
        bitmapWithPadding.recycle()
        return finalBitmap
    }

    var yAxisShift = 0f
    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun handleEventCase(bitmap: Bitmap) {

        if (fullRefreshPositionX == 0f && fullRefreshPositionY == 0f) {
            updatePositionForFullFreshPage(bitmap)
        }

        yAxisShift = bitmap.height.toFloat() / 4f

        val cutBitmapHalf = BitmapCropLib.cropToVerticalMiddleTwo (bitmap)

        //
        // 領取 , 未獲得寵粉紅包雨 , 你贏得了
        //
        recognizeTextAndHandleGesture(cutBitmapHalf) { resultText ->
            processEventCase(resultText, bitmap.height)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun processEventCase(resultText: Text, screenshotHeight: Int) {
        var mNationalCheck = 0

        val regex15 = Regex("^[提媞堤隄瑅捷碍][領领須须後铁]") //提領按鈕
        val regex14 = Regex("[待徍诗侍倚恃律][提媞堤隄瑅捷碍][領领須须後铁][蝦轄遐].") //待提領蝦幣
        val regex13 = Regex("[確碓确碩][認詔定足疋忍]")
        val regex12 = Regex("[國圍園團][家冢豪象].{2}[報執]")
        val regex11 = Regex("[關关][注主]")
        val regex10 = Regex("現在就.?[主王][播搔波插搂]")
        val regex9 = Regex("[您恁你][獲获攥瓉穫狗猹獠][得徳德陽律很傷]\\s*([0-9](?:\\.[0-9]{1,2})?)\\s*[蝦轄遐]")
        val regex8 = Regex("加[活括][動勁]")
        val regex7 = Regex("^再[試式]一次")
        val regex6 = Regex("[網罔]路[連蓮][線銑絏]")
        val regex5 = Regex("[手丰]速[搶抢][紅红][包匃]")
        val regex4 = Regex("[禾未千末朱][獲获攥瓉穫狗猹獠][得徳德陽律很傷]")
        val regex3 = Regex("[獎賞][勵歷周].?派[發髮]")
        val regex2 = Regex("[禾未千末朱][獲获攥瓉穫狗猹獠][得徳德陽律很傷]寵粉")
        val regex1 = Regex("[直置真][播搔波插搂][還這邊遭遣運週]可[領领須须後铁]取")
        var coinValueFind = false
        var coinValue = 0f
        var findSubscribe = false

        var foundRegex14 = false
        var regex15Box: android.graphics.Rect? = null

        try {
            // 先掃描一遍尋找 regex14 和 regex15
            for (block in resultText.textBlocks) {
                for (line in block.lines) {
                    if (regex14.find(line.text) != null) foundRegex14 = true
                    if (regex15.find(line.text) != null) {
                        regex15Box = line.boundingBox
                    }
                }
            }

            // 如果同時滿足條件，執行點擊、延遲 4 秒後返回
            if (foundRegex14 && regex15Box != null) {
                Log.d("processEventCase", "符合特殊任務條件：找到 regex14 並偵測到 regex15 按鈕位置")
                //val clickX = regex15Box.centerX().toFloat()
                //val clickY = realY(regex15Box.centerY().toFloat(), screenshotHeight.toFloat() / 4f)
                
                serviceScope.launch {
                    //touchClick(clickX, clickY)
                    delay(4000L)
                    platformBackGesture()
                }
                return // 中斷後續一般處理
            }

            for (block in resultText.textBlocks) {
                for (line in block.lines) {
                    val matches1 = regex1.find(line.text)
                    val matches2 = regex2.find(line.text)
                    val matches3 = regex3.find(line.text)
                    val matches4 = regex4.find(line.text)
                    val matches5 = regex5.find(line.text)
                    val matches6 = regex6.find(line.text)
                    val matches7 = regex7.find(line.text)
                    val matches8 = regex8.find(line.text)
                    val matches9 = regex9.find(line.text)
                    val matches10 = regex10.find(line.text)
                    val matches11 = regex11.find(line.text)
                    val matches12 = regex12.find(line.text)
                    var matches13: MatchResult? = null

                    if (mNationalCheck == 1) {
                        matches13 = regex13.find(line.text)
                    }


                    Log.d("OCR_Line", "文字內容：${line.text}")
                    //Log.d("OCR_Line", "文字位置：${line.boundingBox}")

                    if (matches11 != null && findSubscribe){
                        line.boundingBox?.let { box ->
                            touchClick(box.centerX().toFloat(), box.bottom.toFloat()  + yAxisShift )
                        }
                        findSubscribe = false
                    }

                    if (matches10 != null ){
                        findSubscribe = true
                    }

                    if (GlobalValueHolder.isOldCompatibilityMode) {
                        var positionYGetCoinButton = 0f
                        if (matches9 != null && !coinValueFind) {
                            Log.d("RegexMatch9", "直播 coin --> ${matches9.groups[1]?.value?.toFloat()}")
                            coinValue = matches9.groups[1]?.value?.toFloat()!!
                            coinValueFind = true

                            Log.d("您獲得.top", " ${line.boundingBox?.top?.toFloat()!!}")
                            // cropToVerticalMiddleTwo 的頂部偏移是高度的 1/4
                            positionYGetCoinButton = realY(line.boundingBox?.top?.toFloat()!!, screenshotHeight.toFloat() / 4f)
                        }

                        if (matches1 != null) {
                            Log.d("RegexMatch", "找到  本場直播還可領取")
                            if (coinValueFind && coinValue != 0f) {
                                Log.d("RegexMatch", "ADDD coinClaimStorage coinValue = ${coinValue}")
                                coinClaimStorage.addClaim(CoinClaim(amount = coinValue.toDouble()))
                                coinValueFind = false
                                floatingService?.updateRecordTextToday()
                            }
                            
                            val currentY = realY(line.boundingBox?.bottom?.toFloat()!!, screenshotHeight.toFloat() / 4f)
                            var positionYGAP = currentY - positionYGetCoinButton
                            if (positionYGetCoinButton == 0f) positionYGAP = 98f

                            val clickY = currentY + positionYGAP * 1.5f + gTotalHeight / 4
                            serviceScope.launch {
                                moveActionMutex.withLock {
                                    touchClick(metrics.widthPixels / 2f, clickY)
                                }
                            }
                        }
                    }

                    if (matches2 != null || matches3 != null || matches4 != null || matches8 != null) {
                        Log.d("RegexMatch", "找到  獎勵派發 or 未獲得寵粉 or 未獲得紅包  or 關注主播參加活動")
                        platformBackGesture()
                    }
                    if (matches5 != null) {
                        CoroutineScope(Dispatchers.Main).launch {
                            repeat(10) {
                                touchClick(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
                                delay(400L)
                            }
                        }
                    }
                    if (matches6 != null) {
                        Log.d("move", "網路連線 with quickRefreshPage")
                        serviceScope.launch {
                            moveActionMutex.withLock {
                                quickRefreshPage()
                            }
                        }
                    }
                    if (matches7 != null) {
                        line.boundingBox?.let { box ->
                            touchClick(box.centerX().toFloat(), box.bottom.toFloat()  + yAxisShift )
                        }
                    }
                    if (matches12 != null) {
                        Log.d("find", "警報")
                        mNationalCheck = 1
                    }
                    if (mNationalCheck == 1 && matches13 != null) {
                        Log.d("find", "警報 點級")
                        line.boundingBox?.let { box ->
                            touchClick(box.centerX().toFloat(), box.bottom.toFloat()  + yAxisShift )
                        }
                        mNationalCheck = 0
                    }
                }
            }
        } finally {

        }

    }


    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun handleCoinCase(bitmap: Bitmap) {
        val cutBitmap = BitmapCropLib.cropToTopRightQuarter(bitmap)
        
        // 1. 準備白色增強圖 (1倍解析度，負擔輕，用於第一階段標題檢查與時間)
        val whiteEnhanced = BitmapCropLib.toWhiteEnhanced(cutBitmap)

        // 進行第一階段辨識 (白色濾鏡)
        recognizeTextAndHandleGesture(whiteEnhanced) { whiteResult ->
            val regex5 = Regex("[直置真百].[閁閂閃閉間閒閱间問简].?[蝦轄遐].")
            val regex6 = Regex("[蝦轄遐].[直置真百].[任住低仟伴尫彺往仁].[獎賞]")
            
            var foundHeader = false
            for (block in whiteResult.textBlocks) {
                for (line in block.lines) {
                    if (regex5.find(line.text) != null || regex6.find(line.text) != null) {
                        foundHeader = true
                        break
                    }
                }
                if (foundHeader) break
            }

            if (foundHeader) {
                // 💡 找到標題，執行後續重型運算：金色濾鏡與放大
                Log.d("HandleCoinCase", "找到蝦幣標題，執行金色濾鏡辨識")
                
                val goldEnhanced = BitmapCropLib.toGoldEnhanced(cutBitmap)
                val goldEnhancedUpscaled = BitmapCropLib.upscaleBitmap(goldEnhanced, upscaleRate.toInt())

                if (GlobalValueHolder.isImageDebugMode) {
                    BitmapCropLib.saveBitmapToGallery(this, goldEnhancedUpscaled, "GoldFilter")
                    BitmapCropLib.saveBitmapToGallery(this, whiteEnhanced, "WhiteFilter")
                }

                TextRecognizerUtil.recognizeTextFromImage(goldEnhancedUpscaled, { goldResult ->
                    processCoinCase(goldResult, whiteResult, bitmap.width)
                    goldEnhanced.recycle()
                    goldEnhancedUpscaled.recycle()
                }, {
                    goldEnhanced.recycle()
                    goldEnhancedUpscaled.recycle()
                    // 即使金色辨識失敗，也要跑大腦邏輯
                    processCoinCase(null, whiteResult, bitmap.width)
                })
            } else {
                // 💡 雖然沒標題，但processCoinCase 必須跑，否則不會翻頁
                Log.d("HandleCoinCase", "未發現蝦幣標題，快速跳過金色運算，進入狀態更新")
                processCoinCase(null, whiteResult, bitmap.width)
            }
            cutBitmap.recycle() // 💡 補上小圖回收，防止記憶體洩漏
        }
    }

    private fun lastNotZero( value:String) :Boolean  // to check not 2.0 or 2.20 should be 2 or 2.2  最後一位不為0
    {
        return value.lastOrNull() != '0'
    }

    private fun realY(inputY: Float, cropTopOffset: Float): Float {
        // 既然採用 1:1 全螢幕擷取，且截圖已包含狀態列，
        // 真實座標 = 局部 OCR 座標 + 裁切位移
        return inputY + cropTopOffset
    }
    var gFindCoinButNoTime = 0


    //第一階段  for (line in block.lines)  確認 "直播間蝦幣"  存在 紀錄flag
    //第二階段 for (line in block.lines)   "直播間蝦幣"  存在 flag = true, 確認 蝦幣數字 (CoinValue) 同時存在
    //第三階段 領取按鈕 與 處理時間倒數

    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    private fun processCoinCase(goldResult: Text?, whiteResult: Text, screenshotWidth: Int) {

        val regex1 = Regex("([0-1]\\.\\d{1,2}|[1])")
        val regex2 = Regex("(10:00)|((0[0-9])(:\\d{0,2}))")
        val regex3 = Regex("[領领須须後铁]取")
        val regex4 = Regex("^\\|?\\(?[重童垂][試拭詩]")
        val regex5 = Regex("[直置真百].[閁閂閃閉間閒閱间問简].?[蝦轄遐].")
        val regex6 = Regex("[蝦轄遐].[直置真百].[任住低低仟伴尫彺往仁].[獎賞]") //蝦皮直播任務獎歷

        var coinPositionX  = 0f
        var coinPositionY  = 0f
        var coinPositionTop = 0f
        var coinPositionBottom = 0f
        var coinPositionHeight  = 0f
        var coinValueToRecord = 0f
        var findLiveCoinHeader = false

        // 紀錄上一輪是否正在等待
        waitingLiveChecker = (coinStates == CState.WAITING_COIN || coinStates == CState.GET_COIN_READY)

        // 備份上一輪狀態，用於判定是否丟失
        val previousState = coinStates
        var nextState = CState.SEARCHING_COIN

                try {
                    var headerBox: android.graphics.Rect? = null

                    // 第一階段：確認 "直播間蝦幣" or "蝦皮直播任務獎歷" 是否存在 - 使用白色濾鏡
                    for (block in whiteResult.textBlocks) {
                        for (line in block.lines) {
                            Log.d("CoinValue", "第一階段文字：${line.text}")
                            if (regex5.find(line.text) != null || regex6.find(line.text) != null  ) {
                                findLiveCoinHeader = true
                                headerBox = line.boundingBox
                                Log.d("CoinValue", "第一階段：找到直播間蝦幣標題，位置：$headerBox")
                                break
                            }
                        }
                    }

                    // 第二階段：若標題存在，尋找 蝦幣數字 (CoinValue) 並記錄位置 - 使用金色濾鏡
                    if (goldResult != null) {
                        for (block in goldResult.textBlocks) {
                            if (findLiveCoinHeader && headerBox != null) {
                                for (line in block.lines) {
                                    val matches1 = regex1.find(line.text)
                                    if (matches1 != null && lastNotZero(matches1.value)) {
                                        val boxcRaw = line.boundingBox
                                        if (boxcRaw != null) {
                                            // 💡 因為 goldResult 是經過 UpscaleRate 倍縮放，座標需還原
                                            val boxc = android.graphics.Rect(
                                                (boxcRaw.left / upscaleRate).toInt(),
                                                (boxcRaw.top / upscaleRate).toInt(),
                                                (boxcRaw.right / upscaleRate).toInt(),
                                                (boxcRaw.bottom / upscaleRate).toInt()
                                            )

                                            val currentHeader = headerBox
                                            val isBelowHeader = boxc.centerY() > currentHeader.centerY()
                                            val isWithinHorizontalBounds = boxc.centerX() >= currentHeader.left && boxc.centerX() <= currentHeader.right

                                            if (isBelowHeader && isWithinHorizontalBounds) {
                                                Log.d("CoinValue", "第二階段：找到符合位置關係的數字 ${matches1.value}")
                                                // cropToTopRightQuarter 的 X 偏移是寬度的一半，Y 偏移是 0
                                                coinPositionX = (boxc.centerX()).toFloat() + (screenshotWidth / 2f)
                                                coinPositionY = realY((boxc.centerY()).toFloat(), 0f)
                                                coinPositionTop = realY(boxc.top.toFloat(), 0f)
                                                coinPositionBottom = realY(boxc.bottom.toFloat(), 0f)
                                                coinPositionHeight = boxc.height().toFloat()

                                                coinValueToRecord = matches1.value.toFloat()
                                                
                                                // 💡 紀錄到歷史清單 (供回溯調用)
                                                roomHistoryMap[currentRoomIndex] = Pair(coinValueToRecord, System.currentTimeMillis())
                                                
                                                // 更新 Debug 資訊
                                                GlobalValueHolder.debugCoinPosValue = "(${coinPositionX.toInt()},${coinPositionY.toInt()})($coinValueToRecord)"
                                                GlobalValueHolder.debugLineText = line.text
                                                GlobalValueHolder.debugLineVal = matches1.value

                                                // 💡 只有符合門檻才標記為「發現」，否則不執行時間與領取邏輯
                                                if (coinValueToRecord >= GlobalValueHolder.coinValueSatisfy) {
                                                    Log.d("CoinValue", "符合門檻：$coinValueToRecord >= ${GlobalValueHolder.coinValueSatisfy}")
                                                    notFindConter = 0
                                                    if (nextState == CState.SEARCHING_COIN) {
                                                        nextState = CState.COIN_VAULE_FIND
                                                    }
                                                } else {
                                                    Log.d("CoinValue", "低於門檻：$coinValueToRecord < ${GlobalValueHolder.coinValueSatisfy}")
                                                    // nextState 保持 SEARCHING_COIN，這會觸發 PAGE_COIN_NOT_FIND 會去更新頁面
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    var isLoopBroken = false

                    // 3. 處理時間倒數 (第三階段) - 💡 只有符合門檻或原本就在等的才「留下來等」
                    if ((nextState == CState.COIN_VAULE_FIND || previousState == CState.WAITING_COIN) && coinPositionX != 0f) {
                        OuterTime@ for (block in whiteResult.textBlocks) {
                            for (line in block.lines) {
                                val matches2 = regex2.find(line.text)
                                if (matches2 != null) {
                                    val boxtRaw = line.boundingBox
                                    if (boxtRaw != null) {
                                        val timey = realY(boxtRaw.top.toFloat(), 0f)
                                        if (timey <= coinPositionY + coinPositionHeight * 3.3f) {
                                            Log.d("RegexMatch", "第三階段：找到倒數時間 ${matches2.value} -> WAITING_COIN")
                                            GlobalValueHolder.debugLineVal = matches2.value
                                            nextState = CState.WAITING_COIN
                                            gFindCoinButNoTime = 0
                                            break@OuterTime
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 處理「領取」按鈕與「重試」(第四階段) - 💡 只要有按鈕出現，不論金額一律點擊
                    OuterReg@ for (block in whiteResult.textBlocks) {
                        for (line in block.lines) {
                            // 處理「領取」按鈕
                            val matches3 = regex3.find(line.text)
                            if (matches3 != null) {
                                val boxg = line.boundingBox
                                if (boxg != null) {
                                    val getCoinRightX = boxg.right.toFloat() + (screenshotWidth / 2f)
                                    val getCoinY = realY(boxg.centerY().toFloat(), 0f)

                                    val isPositionValid = if (coinPositionX != 0f) {
                                        val isRightSide = getCoinRightX > coinPositionX
                                        val tolerance = coinPositionHeight * 0.3f
                                        val isWithinYRange = getCoinY >= (coinPositionTop - tolerance) && getCoinY <= (coinPositionBottom + tolerance)
                                        isRightSide && isWithinYRange
                                    } else {
                                        true
                                    }

                                    if (isPositionValid) {
                                        Log.d("RegexMatch", "第四階段：找到領取按鈕 (不論金額一律領取) -> GET_COIN_READY")
                                        
                                        val targetClickX = boxg.right.toFloat() + (metrics.widthPixels / 2f)
                                        GlobalValueHolder.debugGetPos = "(${targetClickX.toInt()},${getCoinY.toInt()})"

                                        nextState = CState.GET_COIN_READY
                                        gFindCoinButNoTime = 0
                                        floatingService?.resetFloatButtonLocation()

                                        touchClick(targetClickX, getCoinY)

                                        if (coinValueToRecord > 0f) {
                                            if (RecordCionLimiter.canCall()) {
                                                coinClaimStorage.addClaim(CoinClaim(amount = coinValueToRecord.toDouble()))
                                                floatingService?.updateRecordTextToday()
                                                
                                                val (tHour, tMins) = TimeLib.GetTime()
                                                findCurrentStrategy(tHour, tMins, DefaultStrategies)?.let { strategy ->
                                                    GlobalValueHolder.coinValueSatisfy = strategy.periodUpValue
                                                    Log.d("processCoinCase", "領取成功：重置門檻為 ${strategy.periodUpValue}")
                                                }
                                            }
                                        }
                                        serviceScope.launch { delay(3000L) }
                                        isLoopBroken = true
                                        break@OuterReg
                                    }
                                }
                            }

                            // 處理「重試」按鈕 (第四階段)
                            val matches4 = regex4.find(line.text)
                            if (matches4 != null) {
                                Log.d("move", "第四階段：找到重試 -> quickRefreshPage")
                                serviceScope.launch {
                                    moveActionMutex.withLock { quickRefreshPage() }
                                }
                                gFindCoinButNoTime = 0
                                nextState = CState.SEARCHING_COIN
                                isLoopBroken = true
                                break@OuterReg
                            }
                        }
                    }

                    if (!isLoopBroken && nextState != CState.COIN_VAULE_FIND && nextState != CState.WAITING_COIN) {
                        nextState = CState.PAGE_COIN_NOT_FIND
                    }

                    // --- 狀態更新邏輯 (含豁免機制) ---
                    if (previousState == CState.WAITING_COIN && nextState == CState.PAGE_COIN_NOT_FIND) {
                        stateLossCounter++
                        if (stateLossCounter <= 2) {
                            Log.d("StateGuard", "WAITING_COIN 丟失，豁免中 ($stateLossCounter/2)")
                            coinStates = CState.WAITING_COIN // 維持狀態
                        } else {
                            Log.d("StateGuard", "豁免失效，狀態降級")
                            coinStates = nextState
                            stateLossCounter = 0
                        }
                    } else {
                        coinStates = nextState
                        stateLossCounter = 0 // 只要認到東西就重置
                    }
                    // ----------------------------
                    lastState = coinStates // 更新全域狀態供懸浮按鈕變色使用

                    // 第五階段：處理卡bug問題與換頁邏輯
                    if (coinPositionX != 0f && coinPositionY != 0f) {
                        findCoinButNoTimeHandler()
                    }

                    getCoinFreezeHandler()
                    notFindCoinHandler()
                    Log.d("gIsCapturing", "IsCapturing = false")
                } finally { }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun notFindCoinHandler() {
        if (coinStates == CState.PAGE_COIN_NOT_FIND) {
            notFindConter += 1
        }
        
        val limit = if (waitingLiveChecker) 5 else 2
        
        if (notFindConter >= limit){
            Log.d("move", "notFindCoinHandler: 超過容忍次數 $limit -> MoveNextPage")
            coinStates = CState.NOT_FIND_DOING_FRESH
            findNextRoom()
            notFindConter = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getCoinFreezeHandler() {

        if (coinStates == CState.GET_COIN_READY) {
            getCoinFreezeCount += 1
            Log.d("move", "GET_COIN_READY 累計: $getCoinFreezeCount")
        } else {
            getCoinFreezeCount = 0
        }

        val limit = if (waitingLiveChecker) 5 else 2

        if (getCoinFreezeCount > limit){
            Log.d("move", "getCoinFreezeCount 超過 $limit -> quickRefreshPage")
            serviceScope.launch {
                moveActionMutex.withLock {
                    quickRefreshPage()
                }
            }
            getCoinFreezeCount = 0
        }
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun findCoinButNoTimeHandler() {
        gFindCoinButNoTime += 1 // always +1 如果找到 time code 會清 0
        Log.d("move", "FindCoinButNoTime  ===  $gFindCoinButNoTime")
        if (gFindCoinButNoTime > 3){
            Log.d("move", "找到coin 但沒時間 Fix with quickRefreshPage")
            gFindCoinButNoTime = 0
            serviceScope.launch {
                moveActionMutex.withLock {
                    quickRefreshPage()
                }
            }
        }
    }


    private fun checkInLiveStreamingPage(snap_image: Bitmap) {
        Log.d("checkInLiveStreamingPage", "checkInLiveStreamingPage")
        if (!GlobalValueHolder.appCheckRestartFeature || !MyAccessibilityService.isRunning) {
           return
        }
        if (!CheckInLiveStreamLimiter.canCall()) {
            return
        }

        Log.d("checkInLiveStreamingPage", ".canCall()")

        val cut_image = BitmapCropLib.cropToVerticalTopQuarter(snap_image)
        val regex = Regex("[短程].{1}[音言].*?[直置真].{1}")
        val regex1 = Regex(".*?[直置真].{1}.*?[推插揮指種播]")
        val regex2 = Regex("[觀歡難观観][看春着][者考老孝]")

        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = cut_image,
            onResult = { resultText ->
                var isFound = false
                try {
                    OutHere@ for (block in resultText.textBlocks) {
                        for (line in block.lines) {
                            Log.d("OCR_Line Full", "文字內容：${line.text}")
                            //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                            val matches = regex.find(line.text)
                            val matches1 = regex1.find(line.text)
                            val matches2 = regex2.find(line.text)

                            if (matches != null) {
                                Log.d("checkInLiveStreamingPage", "找到 短影音 直播：${matches.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    Log.d("checkInLiveStreamingPage", "在直播頁面")
                                    isFound = true
                                    break@OutHere
                                }
                            }
                            if (matches1 != null) {
                                Log.d("checkInLiveStreamingPage", "找到 直播 推薦：${matches1.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    Log.d("checkInLiveStreamingPage", "在直播頁面")
                                    isFound = true
                                    break@OutHere
                                }
                            }
                            if (matches2 != null) {
                                Log.d("checkInLiveStreamingPage", "找到 觀看者：${matches2.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    Log.d("checkInLiveStreamingPage", "在直播頁面")
                                    isFound = true
                                    break@OutHere
                                }
                            }
                        }
                    }
                }
                finally {
                    cut_image.recycle()
                    if (isFound) {
                        checkLiveStreamingPageRetryCount = 0
                        notInLiveStreamingPageReopenRequest = false
                    } else {
                        checkLiveStreamingPageRetryCount += 1
                        Log.d("checkInLiveStreamingPage", "直播頁面 沒找到 retry = $checkLiveStreamingPageRetryCount ")
                    }
                    if (checkLiveStreamingPageRetryCount > 5) {
                        checkLiveStreamingPageRetryCount = 0
                        notInLiveStreamingPageReopenRequest = true
                    }
                }
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
                cut_image.recycle()
            })
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updatePositionForFullFreshPage(snap_image: Bitmap) {
        fullRefreshPositionX = 0f
        fullRefreshPositionY = 0f
        val cut_image = BitmapCropLib.cropToVerticalTop20percent(snap_image)

        val regex = Regex("[短程].{1}[音言].*?[直置真].{1}")

        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = cut_image,
            onResult = { resultText ->
                try {
                    OutHere@ for (block in resultText.textBlocks) {
                        for (line in block.lines) {
                            Log.d("OCR_Line Full", "文字內容：${line.text}")
                            //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                            val matches = regex.find(line.text)
                            if (matches != null) {
                                Log.d("RegexMatch直播 Full", "找到 短影音 直播 推薦：${matches.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    fullRefreshPositionX = metrics.widthPixels / 2f + boxc.width().toFloat()/7.8f  // hard code, metrics.widthPixels / 2f
                                    fullRefreshPositionY = (boxc.centerY()).toFloat()

                                    if (fullRefreshPositionY >= (metrics.heightPixels) / 5f  || fullRefreshPositionX > (0.75f) * (metrics.widthPixels)){
                                        //
                                        // 如果 fullRefreshPositionY 值有問題(大於五分之一個螢幕Y軸) retry
                                        //
                                        fullRefreshPositionX = 0f
                                        fullRefreshPositionY = 0f
                                        Log.d("RegexMatch直播", "Full_refresh_Position 位置不對")
                                    }

                                    Log.d("RegexMatch直播", "fullRefreshPositionX：${fullRefreshPositionX}, fullRefreshPositionY：${fullRefreshPositionY}, w: ${boxc.width()}")
                                    break@OutHere
                                }
                            }
                        }
                    }
                } finally {
                  cut_image.recycle()
                }
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
                cut_image.recycle()
            })

    }


    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun checkShopeeMainPage() {

        if (findShopeeMainPage)
        {
            return
        }

        val image = imageReader?.acquireLatestImage()
        if(image == null){
            findShopeeMainPage = true
            delay(25000L)
            return
        }
        val bitmap = getBitmapFromImage(image)

        val cut_image = BitmapCropLib.cropToVerticalButton25percent(bitmap)
        bitmap.recycle()

        val regex = Regex("[直置真][播搔波插搂][短程].{1}[音言]")
        val regex1 = Regex("蝦[拼洋]")

        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = cut_image,
            onResult = { resultText ->
                try {
                    findShopeeMainPage = false
                    OutHere@ for (block in resultText.textBlocks) {
                        for (line in block.lines) {
                            Log.d("OCR_Line", "文字內容：${line.text}")
                            val matches = regex.find(line.text)
                            val matches1 = regex1.find(line.text)
                            if (matches != null ) {
                                Log.d("FindShopeeMainPage", "找到 直播短影音 or 蝦拼 in low 25% page, main page：${matches.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    if (boxc.left < ((metrics.widthPixels/2f)  + 50f) && boxc.right > ((metrics.widthPixels/2f)  - 50f)) {
                                        Log.d("FindShopeeMainPage", "找到 直播短影音 in low 25% page  location right")
                                        // cropToVerticalButton25percent 的 Y 偏移是高度의 0.75
                                        mainPageStreamLiveEntryY = realY(boxc.centerY().toFloat(), metrics.heightPixels * 0.75f)
                                        findShopeeMainPage = true
                                        break@OutHere
                                    }
                                }
                            }

                            if (matches1 != null ) {
                                Log.d("FindShopeeMainPage", "找到 直播短影音 or 蝦拼 in low 25% page, main page：${matches1.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    if (boxc.left < ((metrics.widthPixels/3f) )) {
                                        Log.d("FindShopeeMainPage", "找到 蝦拼 in low 25% page  location right")
                                        // cropToVerticalButton25percent 的 Y 偏移是高度의 0.75
                                        mainPageStreamLiveEntryY = realY(boxc.centerY().toFloat(), metrics.heightPixels * 0.75f)
                                        findShopeeMainPage = true
                                        break@OutHere
                                    }
                                }
                            }

                        }
                    }
                } finally {
                    cut_image.recycle()
                    image.close()
                }
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
                cut_image.recycle()
                image.close()
            })
    }



    private suspend fun recognizeTextAndHandleGesture(
        bitmap: Bitmap,
        textAndGestureHandler:  (Text) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = bitmap,
            onResult = { resultText ->
                try {
                    textAndGestureHandler(resultText)
                    cont.resume("ML PASS")
                } catch (e: Exception) {
                        cont.resumeWithException(e)
                } finally {
                    bitmap.recycle()
                }
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
                bitmap.recycle()
                if (cont.isActive)
                    cont.resumeWithException(error)
            })
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun findNextRoom() {
        moveNextPage()
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun fullFreshPage() {

        // 💡 Full Refresh 後順序會亂掉，必須清空歷史
        roomHistoryMap.clear()
        currentRoomIndex = 0

        if (fullRefreshPositionX != 0f && fullRefreshPositionY != 0f) {
            delay(200L)
            touchClick(fullRefreshPositionX, fullRefreshPositionY)
            delay(500L)
            touchClick(fullRefreshPositionX, fullRefreshPositionY)
            delay(1000L)
            moveNextPage()
            delay(300L)
            moveNextPage()
        }

    }

    private fun platformBackGesture () {
        MyAccessibilityService.performBack()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchClick(x: Float, y: Float) {
        val acService = MyAccessibilityService.instance
        acService?.click(x, y, randomized = true)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun quickRefreshPage () {
        delay(30L)
        moveNextPage()
        delay(1600L)
        movePreviousPage ()
        delay(50L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun movePreviousPage () {
        currentRoomIndex--
        if (currentRoomIndex < 0) currentRoomIndex = 0
        
        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val moveDistance  = metrics.heightPixels / GlobalValueHolder.prevMoveFactor
        Log.d("MovePreviousPage", "screenCenterY ：$screenCenterY, moveDistance ：$moveDistance")
        touchUpDown(screenCenterX,screenCenterY - moveDistance, screenCenterY + moveDistance, GlobalValueHolder.prevMoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun moveNextPage () {
        currentRoomIndex++

        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val moveDistance  = metrics.heightPixels / GlobalValueHolder.nextMoveFactor
        Log.d("MoveNextPage", "screenCenterY ：$screenCenterY, moveDistance ：$moveDistance")
        touchUpDown(screenCenterX,screenCenterY + moveDistance, screenCenterY - moveDistance, GlobalValueHolder.nextMoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun moveLeftPage () {
        val screenX = metrics.widthPixels / 4f
        val screenCenterY = metrics.heightPixels / 2f
        val moveDistance  = metrics.widthPixels / 1.5f
        Log.d("moveLeftPage", "screenCenterY ：$screenCenterY, moveDistance ：$moveDistance")
        touchRightLeft(screenX,screenX + moveDistance, screenCenterY, 700)
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun moveRightPage () {
        val screenX = metrics.widthPixels / 4f
        val screenCenterY = metrics.heightPixels / 2f
        val moveDistance  = metrics.widthPixels / 1.5f
        Log.d("moveRightPage", "screenCenterY ：$screenCenterY, moveDistance ：$moveDistance")
        touchRightLeft(screenX + moveDistance, screenX , screenCenterY, 700)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchUpDown (x: Float, yStart: Float, yEnd: Float, moveLong: Long) {
        val acService = MyAccessibilityService.instance
        acService?.swipeBezier(x, yStart , x, yEnd, moveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchRightLeft (xStart: Float, xEnd: Float, y: Float, moveLong: Long) {
        val acService = MyAccessibilityService.instance
        acService?.swipeBezier(xStart, y , xEnd, y, moveLong)
    }

    private fun cleanup() {

        MediaProjectionHolder.resultCode = -1
        MediaProjectionHolder.resultData = null

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }

    private fun isNowInTimeRangeCheck(): Boolean {
        val now = Calendar.getInstance()
        now.timeInMillis = System.currentTimeMillis()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = GlobalValueHolder.StartHour * 60 + GlobalValueHolder.StartMinute
        val endMinutes = GlobalValueHolder.EndHour * 60 + GlobalValueHolder.EndMinute

        return if (!GlobalValueHolder.IsTimeLimit){
           true // 沒有限制，永遠回傳 true
        } else if(startMinutes <= endMinutes) {
            // 一般區間，例如 08:00 ~ 18:00
            nowMinutes in startMinutes..endMinutes
        } else {
            // 跨午夜區間，例如 22:00 ~ 06:00
            nowMinutes >= startMinutes || nowMinutes <= endMinutes
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "screen_capture_channel"
        val channelName = "螢幕擷取服務"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("正在擷取螢幕")
            .setContentText("服務正在背景擷取畫面")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 替換成你的 icon
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopCaptureLoop()
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        stopSelf()
        Log.d("ScreenCaptureService", "App 被滑掉，服務停止")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureLoop()

        if (isBound) {
            unbindService(connection)
            isBound = false
        }

        serviceJob.cancel()
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()

        isRunning = false
    }

}