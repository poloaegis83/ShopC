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

var CoinValueList = mutableListOf (0f)

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var AdaptiveGetCoinButtonY = 0f
    private val UpscaleRate = 2f
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCaptureTime = 0L
    val MoveActionMutex = Mutex()
    var isOn = false
    var CallBack_Interval = 5000L
    var NotFindConter = 0
    var SearchCount = 0
    var CoinValueSatisfy = GlobalValueHolder.DownValue
    var Full_refresh_Position_x  = 0f
    var Full_refresh_Position_y  = 0f

    var GetCoinFreezeCount = 0

    var CoinStates = CState.COIN_START

    //val CoinClaimStorage = CoinClaimStorage(this)

    enum class CState {
        COIN_START ,GET_COIN_READY, WAITING_COIN, SEARCHING_COIN, PAGE_COIN_NOT_FIND, NOT_FIND_DOING_FRESH, COIN_VAULE_FIND
    }

    var gIsCapturing = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val SingleServiceScope = CoroutineScope(singleThreadDispatcher + serviceJob)

    val metrics = Resources.getSystem().displayMetrics

    var IsMLCallback = false
    private lateinit var coinClaimStorage: CoinClaimStorage


    private var floatingService: FloatingButtonService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FloatingButtonService.LocalBinder
            floatingService = binder?.getService()
            Log.d("onServiceConnected", "onServiceConnected  floatingService")
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            floatingService = null
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

    object CoinButtonLimiter {
        private var lastCallTime: Long = 0L  // 儲存上次成功呼叫的時間

        fun canCall(): Boolean {
            val now = System.currentTimeMillis()
            val twoMinutesMillis = 2 * 60 * 1000  // 2分鐘 = 300,000 毫秒

            return if (now - lastCallTime >= twoMinutesMillis) {
                lastCallTime = now
                false
            } else {
                true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        coinClaimStorage = CoinClaimStorage(this)  // this 是 context
        val intent = Intent(this, FloatingButtonService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
            intent?.getParcelableExtra("resultData")
        }

        Log.d("ScreenCaptureService", "Extracted resultCode: $resultCode, resultData is null: ${resultData == null}")

        if (resultCode == RESULT_OK && resultData != null) {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            // TODO: 在這裡啟動 VirtualDisplay 或 imageReader 擷取畫面
            Log.d("ScreenCapture", "MediaProjection 已啟動")

            setupMediaProjection()
            setupVirtualDisplay()
            SearchLogic(true)
            startCaptureLoopNew()
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
                cleanup()  // 釋放資源
                stopSelf()
            }
        }, handler)
    }

    private fun setupVirtualDisplay() {
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi

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
    private var Counter = 0

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startCaptureLoopNew() {
        captureJob = SingleServiceScope.launch {
            while (isActive) {
                if (Counter%10 == 0) {
                    isInExecuteTime = isNowInTimeRangeCheck()
                }
                if(isInExecuteTime){
                    captureScreenFrame()
                    intervalModifier()
                    delay(CallBack_Interval)
                } else {
                    // 不在執行時間，睡個較長時間減輕 CPU 負擔
                    delay(6000L) // 6秒，根據需求調整
                }
                Counter = (Counter + 1) % 10
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
            val startMinutes = strategy.Start_Hour * 60 + strategy.Start_Mins
            val endMinutes = strategy.End_Hour * 60 + strategy.End_Mins
            nowTotalMinutes in startMinutes until endMinutes
        }
    }
/*
    private fun AddCoinList(AddValue :Float){
        CoinValueList.add(AddValue)
    }

    private fun PopCoinList(){
        CoinValueList.removeAt(CoinValueList.lastIndex)
    }

    private fun CleanCoinList(){
        CoinValueList = mutableListOf (0f)
    }
*/

    fun updateFloatButtonText(text: String) {
        if (isBound) {
            floatingService?.updateStatusText(text)
        } else {
            Log.w("ScreenCaptureService", "FloatingButtonService 尚未綁定")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun SearchLogic(IsInit: Boolean) {

        val (T_hour, T_mins) = TimeLib.GetTime()
        var UpValueNow = 0f
        var DownValueNow = 0f
        var MinusValueNow = 0f
        var RefreshCountNow = 10
        val currentTStrategy = findCurrentStrategy(T_hour, T_mins, DefaultStrategies)

        if (currentTStrategy != null) {
            Log.d("CoinStrategy", "目前時段策略：$currentTStrategy")
            UpValueNow    = currentTStrategy.PeriodUpValue
            DownValueNow  = currentTStrategy.PeriodDownValue
            MinusValueNow = currentTStrategy.CoinValueMinus
            RefreshCountNow = currentTStrategy.RefreshCount
        } else {
            Log.d("CoinStrategy", "目前沒有適用的策略")
            UpValueNow = 0.3f
            DownValueNow = 0.2f
            MinusValueNow = 0.1f
            RefreshCountNow = 5
        }
        if (GlobalValueHolder.DownValue != 0f) {
            //
            // Override by User input
            //
            DownValueNow = GlobalValueHolder.DownValue
        }
        if (IsInit) {
            CoinValueSatisfy = UpValueNow
        } else {
            if (CoinStates == CState.NOT_FIND_DOING_FRESH) {
                Log.d("SearchLogic", "CState.PAGE_COIN_NOT_FIND SearchCount += 1")
                SearchCount += 1
            } else if (CoinStates == CState.WAITING_COIN || CoinStates == CState.GET_COIN_READY){
                SearchCount = 0
            }
            if (CoinStates == CState.WAITING_COIN) {
                Log.d("SearchLogic", "CState.WAITING_COIN")
            }
            if (CoinStates == CState.GET_COIN_READY) {
                Log.d("SearchLogic", "CState.GET_COIN_READY CoinValueSatisfy = UpValueNow")
                CoinValueSatisfy = UpValueNow
            }

            Log.d("SearchLogic", "SearchCount = $SearchCount, CoinValueSatisfy = $CoinValueSatisfy")
            if (CoinStates  == CState.NOT_FIND_DOING_FRESH) {
                if (SearchCount > RefreshCountNow) {
                    SearchCount = 0
                    if (CoinValueSatisfy - MinusValueNow >= DownValueNow) {
                        CoinValueSatisfy -= MinusValueNow
                    }
                    serviceScope.launch {
                        Log.d("SearchLogic", "FullFreshPage")
                        FullFreshPage()
                    }
                }
            }
        }
    }

    private fun intervalModifier () {

        if (!MyAccessibilityService.isRunning) {
            Log.e("updateFloatButtonText", "錯誤:請重開無障礙服務")
            updateFloatButtonText("❌:請打開(重開)無障礙服務")
            CallBack_Interval = 6000L
            return
        } else {
            if (!isOn) {
                updateFloatButtonText("已暫停 點擊打開")
                Log.d("OCR_Line", "Feature Close")
                return
            }
        }

        if (CoinStates == CState.WAITING_COIN) {
            CallBack_Interval = 10000L
            if(GlobalValueHolder.IsLowEndDevice){
                CallBack_Interval = (CallBack_Interval.toFloat() * 3.01f).toLong()
            }
            Log.d("CallBack_Interval", " Long time ")
            updateFloatButtonText("等待蝦幣完成")
        } else {
            CallBack_Interval = 4800L
            if(GlobalValueHolder.IsLowEndDevice){
                CallBack_Interval = (CallBack_Interval.toFloat() * 2.1f).toLong()
            }
            Log.d("CallBack_Interval", " Short Time")
            updateFloatButtonText("尋找蝦幣中")
        }
        Log.d("CallBack_Interval", "Normal Mode → Interval: $CallBack_Interval ms")
    }

    private fun StartAndCheckSkip():Boolean {
        if (gIsCapturing) {
            Log.d("gIsCapturing", "gIsCapturing yes")
            return true
        } else {
            Log.d("gIsCapturing", "gIsCapturing no")
        }

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        isOn = prefs.getBoolean("OCR_ENABLED", false)

        if (isOn) {
            Log.d("OCR_Line", "Feature Open")
        } else {
            Log.d("OCR_Line", "Feature Close")
            return true
        }

        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun captureScreenFrame() {
        Log.d("CoinStates", "$CoinStates")
        if (StartAndCheckSkip()) {
            //imageReader?.acquireLatestImage()?.close()
            gIsCapturing = false
            Log.d("captureScreenFrame", "SKIP")
            return
        }
        SearchLogic(false)

        Log.d("captureScreenFrame", "GO")
        gIsCapturing = true

        Log.d("acquireLatestImage", "acquireLatestImage Start")
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            gIsCapturing = false
            return
        }
        Log.d("acquireLatestImage", "acquireLatestImage End")

        TimeLib.GetTime()
        if (CoinStates == CState.NOT_FIND_DOING_FRESH){
            delay(500L)
        }

        var bitmap: Bitmap? = null
        try {
            bitmap = getBitmapFromImage(image)
            HandleEventCase(bitmap)
            HandleCoinCase(bitmap)
        } catch (e: Exception) {
            Log.e("processImage", "Error: ${e.message}")
        } finally {
            bitmap?.recycle()  // 安全回收
            image.close()      // 永遠記得關掉 Image buffer
        }

        gIsCapturing = false
    }

    private fun getBitmapFromImage( imageIn: Image):Bitmap{
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels

        val density = Resources.getSystem().displayMetrics.densityDpi
        Log.d("displayMetrics.width  Pixels", "width =: ${width} , height =: ${height}")
        val planes = imageIn.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = createBitmap(imageIn.width + rowPadding / pixelStride, imageIn.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    var Y_axis_shift = 0f
    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun HandleEventCase(bitmap: Bitmap) {

        if (Full_refresh_Position_x == 0f && Full_refresh_Position_y == 0f) {
            UpdatePositionForFullFreshPage(bitmap)
        }

        Y_axis_shift = bitmap.height.toFloat() / 4f

        val cutBitmapHalf = BitmapCropLib.cropToVerticalMiddleTwo (bitmap)

        //
        // 領取 , 未獲得寵粉紅包雨 , 你贏得了
        //
        recognizeTextAndHandleGesture(cutBitmapHalf, this) { resultText ->
            processEventCase(resultText)
        }

    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun processEventCase(resultText: Text) {
        var positionYGetCoinButton = 0f
        var positionYGAP = 0f

        val regex9 = Regex("您獲得\\s*([0-9]\\.[0-9]{1,2})\\s*蝦")
        val regex8 = Regex("加活動")
        val regex7 = Regex("^再[試式]一次")
        val regex6 = Regex("[網罔]路[連蓮]線")
        val regex5 = Regex("手速搶紅包")
        val regex4 = Regex("[禾未千末朱]獲得")
        val regex3 = Regex("[獎賞][勵歷周].?派[發髮]")
        val regex2 = Regex("[禾未千末朱]獲得寵粉")
        val regex1 = Regex("[直置真][播波插]還可[領领]取")
        var coinValueFind = false
        var coinValue = 0f

        try {
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

                    Log.d("OCR_Line", "文字內容：${line.text}")
                    //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                    if (matches9 != null && !coinValueFind) {
                        if (RecordCionLimiter.canCall()){
                            Log.d("RegexMatch", "直播 coin --> ${matches9.groups[1]?.value?.toFloat()}")
                            coinValue = matches9.groups[1]?.value?.toFloat()!!
                            coinValueFind = true
                            positionYGetCoinButton = realY(line.boundingBox?.top?.toFloat()!!,1)
                            Log.d("Positon_Y_GetCoinButton", "positionYGetCoinButton --> ${positionYGetCoinButton}")
                        }
                    }

                    if (matches1 != null) {
                        Log.d("RegexMatch", "找到  本場直播還可領取")
                        if (coinValueFind && coinValue != 0f) {
                            Log.d("RegexMatch", "ADDD coinClaimStorage")
                            coinClaimStorage.addClaim(CoinClaim(amount = coinValue.toDouble()))
                            coinValueFind = false
                        }

                        positionYGAP = realY(line.boundingBox?.bottom?.toFloat()!!,1) - positionYGetCoinButton

                        positionYGetCoinButton = realY(line.boundingBox?.bottom?.toFloat()!!,1) + positionYGAP *1.5f + gTotalHeight/4
                        Log.d("Positon_Y_GetCoinButton222", "Positon_Y_GAP --> ${positionYGAP }, Positon_Y_GetCoinButton --> ${positionYGetCoinButton}")
                        if (AdaptiveGetCoinButtonY == 0f){
                            AdaptiveGetCoinButtonY = positionYGetCoinButton
                        }
                        val retry_gap = 10f

                        if (CoinButtonLimiter.canCall()) {
                            //
                            // Only Adaptive Get Coin Button, triggered twice in 2 mins
                            //
                            if (AdaptiveGetCoinButtonY <= positionYGetCoinButton + positionYGAP ) {
                                AdaptiveGetCoinButtonY += retry_gap
                            } else {
                                AdaptiveGetCoinButtonY -= 80f
                            }
                            Log.d("AdaptiveGetCoinButtonY update", "AdaptiveGetCoinButtonY update--> ${AdaptiveGetCoinButtonY}")
                        }
                        Log.d("AdaptiveGetCoinButtonY", "AdaptiveGetCoinButtonY --> ${AdaptiveGetCoinButtonY}")
                        serviceScope.launch {
                            MoveActionMutex.withLock {
                                GetCoinButton(metrics.widthPixels / 2f, AdaptiveGetCoinButtonY)
                            }
                        }
                    }
                    if (matches2 != null || matches3 != null || matches4 != null || matches8 != null) {
                        Log.d("RegexMatch", "找到  獎勵派發 or 未獲得寵粉 or 未獲得紅包  or 關注主播參加活動")
                        PlatformBackGesture()
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
                        Log.d("move", "網路連線 with QuickRefreshPage")
                        serviceScope.launch {
                            MoveActionMutex.withLock {
                                QuickRefreshPage()
                            }
                        }
                    }
                    if (matches7 != null) {
                        line.boundingBox?.let { box ->
                            touchClick(box.centerX().toFloat(), box.bottom.toFloat()  + Y_axis_shift )
                        }
                    }
                }
            }
        } finally {

        }

    }


    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun HandleCoinCase(bitmap: Bitmap) {

        val cutBitmap = BitmapCropLib.cropToTopRightEighth (bitmap)

        recognizeTextAndHandleGesture(cutBitmap, this) { resultText ->
            processCoinCase(resultText)
        }
    }

    private fun last_notZero( Value:String) :Boolean  // to check not 2.0 or 2.20 should be 2 or 2.2  最後一位不為0
    {
        return if (Value.lastOrNull() == '0'){
            false
        }else {
            true
        }
    }

    private fun realY (inputY: Float, Phase: Int): Float{
        var realY = 0f
        if ( Phase == 1 ) {
            realY = inputY + gHeightOffset/3
        } else if ( Phase == 2 ) {
            realY = inputY + gHeightOffset/3
        }
        return realY
    }
    var gFindCoinButNoTime = 0

    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    private fun processCoinCase(resultText: Text) {

        val regex1 = Regex("(^\\(\\d\$)|(^\\d\$)|(\\d\\.\\d{1,2})")
        val regex2 = Regex("(10:00)|((0[0-9])(:\\d{0,2}))")
        val regex3 = Regex("^[領领]取")
        val regex4 = Regex("^重試")
        //val regex5 = Regex("已結束")

        var Coin_Position_x  = 0f
        var Coin_Position_y  = 0f
        var Coin_Position_Height  = 0f

        CoinStates = CState.SEARCHING_COIN

                try {
                    OuterReg@ for (block in resultText.textBlocks) {
                        for (line in block.lines) {
                            Log.d("OCR_Line", "文字內容：${line.text}")
                            //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                            val matches1 = regex1.find(line.text)
                            if (matches1 != null  && last_notZero(matches1.value)) {
                                Log.d("RegexMatch", "找到Coin：${matches1.value}")
                                Log.d("OCR_Line", "位置：${line.boundingBox}")
                                val CoinValue = matches1.value.toFloat()
                                Log.d("CoinValue", "CoinValue：${CoinValue}")
                                //CoinValueShows = 1
                                val boxc = line.boundingBox
                                if (boxc != null) {
                                    //Coin_Position_x = (boxc.centerX()).toFloat() / UpscaleRate   // 縮放 with UpscaleRate
                                    //Coin_Position_y = (boxc.centerY()).toFloat() / UpscaleRate   // 縮放 with UpscaleRate
                                    Coin_Position_x = (boxc.centerX()).toFloat()
                                    Coin_Position_y = realY ((boxc.centerY()).toFloat(),2)
                                    Coin_Position_Height = boxc.height().toFloat()
                                    Log.d("CoinValue", "boxc.bottom：${boxc.bottom} , boxc.top：${boxc.top}, boxc.centerY() ${boxc.centerY()} ,  height: = ${boxc.height()}")

                                    Coin_Position_x += (metrics.widthPixels / 2f)+ (metrics.widthPixels / 4f)   // X 只有 1/2 + 1/4 必須加位置
                                    //Coin_Position_x += (metrics.widthPixels / 2f)
                                    //Coin_Position_y += (metrics.heightPixels / 8f)   // Y 必須加 1/8 位置
                                    Log.d("CoinValue p", "Coin_Position_x：${Coin_Position_x}  Coin_Position_y：${Coin_Position_y}")

                                    //CoinStates = CState.COIN_VAULE_FIND
                                }

                                if (CoinValue >= CoinValueSatisfy ){
                                    Log.d("CoinValue p", "Coin COIN_VAULE_FIND  滿足")
                                    CoinStates = CState.COIN_VAULE_FIND
                                    NotFindConter = 0
                                }
                            }

                           // val matches5 = regex5.find(line.text)
                           // if (matches5 != null) {
                           //     Log.d("RegexMatch", "已結束")
                           //     serviceScope.launch {
                           //         moveNextPage()
                           //     }
                           // }

                            val matches3 = regex3.find(line.text)
                            if( matches3 != null) {
                                val boxg = line.boundingBox
                                if (boxg != null) {
                                    if ( realY(boxg.centerY().toFloat(),2) >= Coin_Position_y) {  //check Get Coin Low Than Value  確保領取是比 coin 數字低
                                        Log.d("RegexMatch", "找到Coin 領取：${matches3.value}")
                                        Log.d("OCR_Line", "位置：${line.boundingBox}")
                                        CoinStates = CState.GET_COIN_READY
                                        if (Coin_Position_x != 0f && Coin_Position_y != 0f) {
                                            Log.d("點螢幕", "位置：${Coin_Position_x} , ${Coin_Position_y}")
                                            touchClick(Coin_Position_x, Coin_Position_y)
                                        }
                                        break@OuterReg
                                    } else {
                                        Log.d("RegexMatch", "找到Coin 領取：${matches3.value}  但沒有比較低")
                                    }
                                }
                            }

                            if (Coin_Position_x != 0f && Coin_Position_y != 0f) {
                               val matches2 = regex2.find(line.text)
                                 if (matches2 != null) {
                                    Log.d("OCR_Line", "Coin T位置：${line.boundingBox}")
                                    val boxt = line.boundingBox
                                    if ( boxt != null){
                                        //val timex = boxt.centerX().toFloat()
                                        val timey = realY( boxt.top.toFloat(),2)
                                        Log.d("OCR_Line", "Coin T位置 timey：${timey}")
                                        if (timey <= Coin_Position_y + Coin_Position_Height*2.7){
                                            //
                                            // Check Coin Time 位置
                                            //
                                            Log.d("RegexMatch", "找到Coin Time：${matches2.value}")
                                            //Log.d("OCR_Line", "位置：${line.boundingBox}")
                                            gFindCoinButNoTime = 0

                                            Log.d("CoinStates", "CoinStates：${CoinStates}")
                                            if(CoinStates == CState.COIN_VAULE_FIND) {
                                                //
                                                // 有時間才算找到
                                                //
                                                Log.d("RegexMatch", "有時間才算找到")
                                                CoinStates = CState.WAITING_COIN
                                                break@OuterReg
                                            }
                                        }
                                    }
                                 }

                                val matches4 = regex4.find(line.text) //重試
                                if (matches4 != null) {
                                    Log.d("move", "重試 with QuickRefreshPage")
                                    serviceScope.launch {
                                        MoveActionMutex.withLock {
                                            QuickRefreshPage()
                                        }
                                    }
                                    gFindCoinButNoTime = 0
                                    CoinStates = CState.SEARCHING_COIN
                                    break@OuterReg
                                }
                            }

                            if (CoinStates == CState.COIN_VAULE_FIND) {
                                Log.d("CoinStates", "CState.COIN_VAULE_FIND")
                            } else {
                                //
                                // W/A for Coin value = 1, 找到時間 但沒有 coin value, assume it is 1
                                //
                                //if (matches2 != null){
                                //   if (CoinValueShows == 0) {
                                //        CoinStates = CState.WAITING_COIN
                               //         Log.d("RegexMatchWA", "RegexMatch WA 找到時間 但沒有 coin value")
                               //     }
                               //     break@OuterReg
                                //}
                                //
                                // W/A for Coin value = 1, 找到時間 但沒有 coin value, assume it is 1
                                //
                                CoinStates = CState.PAGE_COIN_NOT_FIND
                            }
                        }
                    }

                    //
                    // 找到coin 但沒時間 Fix with QuickRefreshPage
                    //
                    if (Coin_Position_x != 0f && Coin_Position_y != 0f) {
                        FindCoinButNoTimeHandler()
                    }

                    GetCoinFreezeHandler()
                    NotFindCoinHandler()
                    Log.d("gIsCapturing", "IsCapturing = false")

                } finally {

                }

    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun NotFindCoinHandler() {
        if (CoinStates == CState.PAGE_COIN_NOT_FIND) {
            NotFindConter += 1
        }
        if (NotFindConter > 2){
            Log.d("move", "MoveNextPage")
            CoinStates = CState.NOT_FIND_DOING_FRESH
            //AddCoinList(CoinValueToRecord)
            FindNextRoom()
            NotFindConter = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun GetCoinFreezeHandler() {

        if (CoinStates == CState.GET_COIN_READY) {
            //
            // 如果 GET_COIN_READY 太多次 代表 主播可能跑了
            //
            GetCoinFreezeCount += 1
            Log.d("move", "GET_COIN_READY 太多次 代表 主播可能跑了 +1")
        } else {
            Log.d("move", "GET_COIN_READY = 0")
            GetCoinFreezeCount = 0
        }

        if (GetCoinFreezeCount >= 2){
            Log.d("move", "GetCoinFreezeCount >= 2")
            serviceScope.launch {
                MoveActionMutex.withLock {
                    QuickRefreshPage()
                }
            }
            GetCoinFreezeCount = 0
        }
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun FindCoinButNoTimeHandler() {
        gFindCoinButNoTime += 1 // always +1 如果找到 time code 會清 0
        Log.d("move", "FindCoinButNoTime  ===  $gFindCoinButNoTime")
        if (gFindCoinButNoTime > 3){
            Log.d("move", "找到coin 但沒時間 Fix with QuickRefreshPage")
            gFindCoinButNoTime = 0
            serviceScope.launch {
                MoveActionMutex.withLock {
                    QuickRefreshPage()
                }
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun UpdatePositionForFullFreshPage(snap_image: Bitmap) {
        Full_refresh_Position_x = 0f
        Full_refresh_Position_y = 0f
        val cut_image = BitmapCropLib.cropToVerticalTopQuarter(snap_image)

        val regex = Regex("短.{1}音.*?直.{1}")

        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = cut_image,
            context = this, // activity context
            onResult = { resultText ->
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
                                Full_refresh_Position_x = metrics.widthPixels / 2f + boxc.width().toFloat()/8.5f  // hard code, metrics.widthPixels / 2f
                                Full_refresh_Position_y = (boxc.centerY()).toFloat()
                                Log.d("RegexMatch直播", "Full_refresh_Position_x：${Full_refresh_Position_x}, Full_refresh_Position_y：${Full_refresh_Position_y}, w: ${boxc.width()}")
                                break@OutHere
                            }
                        }
                    }
                }
            },
            onError = { error ->
                Log.e("OCR_Result", "辨識錯誤：${error.message}")
            })

    }



    private suspend fun recognizeTextAndHandleGesture(
        bitmap: Bitmap,
        context: Context,
        textAndGestureHandler:  (Text) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = bitmap,
            context = context,
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
    private fun FindNextRoom() {
        moveNextPage()
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun FullFreshPage() {

        if (Full_refresh_Position_x != 0f && Full_refresh_Position_y != 0f) {
            serviceScope.launch {
                delay(200L)
                touchClick(Full_refresh_Position_x, Full_refresh_Position_y)
                //delay(1100L)
                //touchClick(Full_refresh_Position_x, Full_refresh_Position_y)
                delay(1000L)
                moveNextPage()
                delay(300L)
                moveNextPage()
            }
        }

    }

    private fun PlatformBackGesture () {
        MyAccessibilityService.performBack()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun GetCoinAndQuickRefreshPage () {
        delay(100L)
        PlatformBackGesture()
        delay(1500L)
        QuickRefreshPage()
        delay(100L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun GetCoinButton (x:Float, y:Float) {
        touchClick(x, y)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun QuickRefreshPage () {
        delay(30L)
        moveNextPage()
        delay(1600L)
        movePreviousPage ()
        delay(50L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun movePreviousPage () {
        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val MoveDistance  = metrics.heightPixels / 2.5f
        Log.d("MovePreviousPage", "screenCenterY ：${screenCenterY}, MoveDistance ：${MoveDistance}")
        touchUpDown(screenCenterX,screenCenterY - MoveDistance, screenCenterY + MoveDistance, 900)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun moveNextPage () {
        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val MoveDistance  = metrics.heightPixels / 2.5f
        Log.d("MoveNextPage", "screenCenterY ：${screenCenterY}, MoveDistance ：${MoveDistance}")
        touchUpDown(screenCenterX,screenCenterY + MoveDistance, screenCenterY - MoveDistance, 900)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchUpDown (X: Float, Y_S: Float, Y_E: Float, MoveLong: Long) {
        val ACservice = MyAccessibilityService.instance
        ACservice?.swipe(X, Y_S , X, Y_E, MoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchRightLeft (X: Float, Y_S: Float, Y_E: Float, MoveLong: Long) {
        val ACservice = MyAccessibilityService.instance
        ACservice?.swipe(X, Y_S , X, Y_E, MoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun touchClick (X: Float, Y: Float) {
        val ACservice = MyAccessibilityService.instance
        ACservice?.click(X, Y)
    }

    private fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }

    private fun isNowInTimeRangeCheck(): Boolean {
        val now = Calendar.getInstance()
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
        mediaProjection?.stop()
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
        mediaProjection?.stop()
    }

}