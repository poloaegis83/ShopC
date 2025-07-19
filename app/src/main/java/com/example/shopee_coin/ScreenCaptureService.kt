package com.example.shopee_coin

import android.app.Activity.RESULT_OK
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

var CoinValueList = mutableListOf (0f)

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCaptureTime = 0L

    var CallBack_Interval = 5000L
    var NotFindConter = 0
    var SearchCount = 0
    var CoinValueSatisfy = GlobalValueHolder.DownValue
    var Full_refresh_Position_x  = 0f
    var Full_refresh_Position_y  = 0f

    var GetCoinFreezeCount = 0

    var CoinStates = CState.COIN_START

    enum class CState {
        COIN_START ,GET_COIN_READY, WAITING_COIN, SEARCHING_COIN, PAGE_COIN_NOT_FIND, NOT_FIND_DOING_FRESH, WAITING_COIN_BUT_NOT_FIND_TIME
    }

    var gIsCapturing = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val SingleServiceScope = CoroutineScope(singleThreadDispatcher + serviceJob)

    val metrics = Resources.getSystem().displayMetrics

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

        return START_NOT_STICKY
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
                    isInExecuteTime = IsNowInTimeRangeCheck()
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
    }

    fun findCurrentStrategy(hour: Int, mins: Int, strategies: List<CoinStrategy>): CoinStrategy? {
        val nowTotalMinutes = hour * 60 + mins

        return strategies.find { strategy ->
            val startMinutes = strategy.Start_Hour * 60 + strategy.Start_Mins
            val endMinutes = strategy.End_Hour * 60 + strategy.End_Mins
            nowTotalMinutes in startMinutes until endMinutes
        }
    }

    private fun AddCoinList(AddValue :Float){
        CoinValueList.add(AddValue)
    }

    private fun PopCoinList(){
        CoinValueList.removeAt(CoinValueList.lastIndex)
    }

    private fun CleanCoinList(){
        CoinValueList = mutableListOf (0f)
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
        if (CoinStates == CState.WAITING_COIN) {
            CallBack_Interval = 10000L
            if(GlobalValueHolder.IsLowEndDevice){
                CallBack_Interval = (CallBack_Interval.toFloat() * 3.01f).toLong()
            }
            Log.d("CallBack_Interval", " Long time ")
        } else {
            CallBack_Interval = 4800L
            if(GlobalValueHolder.IsLowEndDevice){
                CallBack_Interval = (CallBack_Interval.toFloat() * 2.1f).toLong()
            }
            Log.d("CallBack_Interval", " Short Time")
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

        /*if (CoinStates == CState.WAITING_COIN) {
            if (!CheckTime_interval_OK()){
                return true
            }
        }*/

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val isOn = prefs.getBoolean("OCR_ENABLED", false)
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

        val bitmap = GetBitmapFromImage(image)
        HandleEventCase (bitmap)
        HandleCoinCase (bitmap)

        bitmap.recycle()
        image.close()
        gIsCapturing = false
    }

    private fun GetBitmapFromImage( imageIn: Image):Bitmap{
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi

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

        Y_axis_shift = bitmap.width.toFloat() / 4f

        val cutBitmapHalf = BitmapCropLib.cropToVerticalMiddleTwo (bitmap)

        //
        // 領取 , 未獲得寵粉紅包雨 , 你贏得了
        //
        processEventCaseImage(cutBitmapHalf)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun processEventCaseImage(image: Bitmap) {
        return suspendCoroutine { cont ->

            val regex8 = Regex("加活動")
            val regex7 = Regex("^再試一次")
            val regex6 = Regex("網路連線")
            val regex5 = Regex("寵粉紅包雨")
            val regex4 = Regex("未獲得")
            val regex3 = Regex("獎勵派發")
            val regex2 = Regex("未獲得寵粉")
            val regex1 = Regex("本場直播還可領取")

            TextRecognizerUtil.recognizeTextFromImage(
                bitmap = image,
                context = this, // activity context
                onResult = { resultText ->
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

                                Log.d("OCR_Line", "文字內容：${line.text}")
                                //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                                if (matches1 != null) {
                                    Log.d("RegexMatch", "找到  本場直播還可領取")
                                    serviceScope.launch {
                                        GetCoinAndQuickRefreshPage()
                                    }
                                }
                                if (matches2 != null || matches3 != null || matches4 != null || matches8 != null) {
                                    Log.d("RegexMatch", "找到  獎勵派發 or 未獲得寵粉 or 未獲得紅包  or 關注主播參加活動")
                                    PlatformBackGesture()

                                }
                                if (matches5 != null) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        repeat(10) {
                                            TouchClick(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
                                            delay(400L)
                                        }
                                    }
                                }
                                if (matches6 != null) {
                                    serviceScope.launch {
                                        QuickRefreshPage()
                                    }
                                }
                                if (matches7 != null) {
                                    line.boundingBox?.let { box ->
                                        TouchClick(box.centerX().toFloat(), box.centerY().toFloat() + Y_axis_shift )
                                    }
                                }
                            }
                        }
                    } finally {
                        image.recycle()
                        cont.resume(Unit) // ✅ 確保 coroutine 結束
                    }
                },
                onError = { error ->
                    Log.e("OCR_Result", "辨識錯誤：${error.message}")
                    image.recycle()
                    cont.resume(Unit) // ✅ 即使錯誤也要 resume
                }
            )
        }
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun HandleCoinCase(bitmap: Bitmap) {

        //val cutBitmap = BitmapCropLib.cropToTopRightQuarter (bitmap)
        var cutBitmap = BitmapCropLib.cropToTopRightEighth (bitmap)

        cutBitmap = BitmapCropLib.upscaleBitmap(cutBitmap, 4)

        processCoinCaseImage(cutBitmap)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun processCoinCaseImage(image: Bitmap) {
        return suspendCoroutine { cont ->

            val regex1 = Regex("(\\d\$)|(\\d\\.\\d{1,2})")
            val regex2 = Regex("(10\\:00)|((0[0-9])(\\:\\d{0,2}))")
            val regex3 = Regex("領取")
            val regex4 = Regex("重試")

            var Coin_Position_x  = 0f
            var Coin_Position_y  = 0f

            var CoinValueToRecord = 0f
            var FindCoinButNoTime = 0
            CoinStates = CState.SEARCHING_COIN

            TextRecognizerUtil.recognizeTextFromImage(
                bitmap = image,
                context = this, // activity context
                onResult = { resultText ->
                    try {
                        OuterReg@ for (block in resultText.textBlocks) {
                            for (line in block.lines) {
                                //Log.d("OCR_Line", "文字內容：${line.text}")
                                //Log.d("OCR_Line", "文字位置：${line.boundingBox}")
                                val matches1 = regex1.find(line.text)
                                if (matches1 != null){
                                    Log.d("RegexMatch", "找到Coin：${matches1.value}")
                                    Log.d("OCR_Line", "位置：${line.boundingBox}")
                                    val CoinValue = matches1.value.toFloat()
                                    Log.d("CoinValue", "CoinValue：${CoinValue}")
                                    //Log.d("GlobalValueHolder UpValue", "UpValue：${GlobalValueHolder.UpValue}")
                                    //Log.d("GlobalValueHolder DownValue", "DownValue：${GlobalValueHolder.DownValue}")
                                    CoinValueToRecord = CoinValue
                                    if (CoinValue >= CoinValueSatisfy ){
                                        CoinStates = CState.WAITING_COIN
                                        NotFindConter = 0
                                        val boxc = line.boundingBox
                                        if (boxc != null) {
                                            Coin_Position_x = (boxc.centerX()).toFloat()
                                            Coin_Position_y = (boxc.centerY()).toFloat()
                                            Coin_Position_x += metrics.widthPixels / 2f + metrics.widthPixels / 4f   // X 只有 1/2 + 1/4 必須加位置
                                            Log.d("CoinValue p", "Coin_Position_x：${Coin_Position_x}  Coin_Position_y：${Coin_Position_y}")
                                        }
                                    }
                                }

                                val matches3 = regex3.find(line.text)
                                if( matches3 != null) {
                                    Log.d("RegexMatch", "找到Coin 領取：${matches3.value}")
                                    Log.d("OCR_Line", "位置：${line.boundingBox}")
                                    CoinStates = CState.GET_COIN_READY
                                    if (Coin_Position_x != 0f && Coin_Position_y != 0f) {
                                        Log.d("點螢幕", "位置：${Coin_Position_x} , ${Coin_Position_y}")
                                        TouchClick(Coin_Position_x, Coin_Position_y)
                                    }
                                    break@OuterReg
                                }

                                if (CoinStates == CState.WAITING_COIN) {
                                    //Log.d("CoinStates", "CState.WAITING_COIN")
                                    val matches2 = regex2.find(line.text)
                                    val matches4 = regex4.find(line.text)
                                    if (matches2 != null){
                                        Log.d("RegexMatch", "找到Coin Time：${matches2.value}")
                                        Log.d("OCR_Line", "位置：${line.boundingBox}")
                                        FindCoinButNoTime = 0
                                        break@OuterReg
                                    }
                                    if (matches4 != null) {
                                        serviceScope.launch {
                                            QuickRefreshPage()
                                        }
                                        FindCoinButNoTime = 0
                                        CoinStates = CState.SEARCHING_COIN
                                        break@OuterReg
                                    }
                                    //
                                    // 找到coin 但沒時間 Fix with QuickRefreshPage
                                    //
                                    FindCoinButNoTime += 1
                                    if (FindCoinButNoTime > 3){
                                        serviceScope.launch {
                                            QuickRefreshPage()
                                        }
                                    }
                                } else {
                                    CoinStates = CState.PAGE_COIN_NOT_FIND
                                }
                            }
                        }

                        GetCoinFreezeHandler()
                        NotFindCoinHandler()
                        Log.d("gIsCapturing", "IsCapturing = false")

                    } finally {
                        image.recycle()
                        cont.resume(Unit) // ✅ 確保 coroutine 結束
                    }
                },
                onError = { error ->
                    Log.e("OCR_Result", "辨識錯誤：${error.message}")
                    image.recycle()
                    cont.resume(Unit) // ✅ 即使錯誤也要 resume
                }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun NotFindCoinHandler() {
        if (CoinStates == CState.PAGE_COIN_NOT_FIND) {
            NotFindConter += 1
        }
        if (NotFindConter >= 2){
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

        if (GetCoinFreezeCount > 2){
            serviceScope.launch {
                QuickRefreshPage()
            }
            GetCoinFreezeCount = 0
        }

    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun UpdatePositionForFullFreshPage(snap_image: Bitmap) {
        Full_refresh_Position_x = 0f
        Full_refresh_Position_y = 0f
        val cut_image = BitmapCropLib.cropToVerticalTopQuarter(snap_image)

        val regex = Regex("直播")
        TextRecognizerUtil.recognizeTextFromImage(
            bitmap = cut_image,
            context = this, // activity context
            onResult = { resultText ->
                OutHere@ for (block in resultText.textBlocks) {
                    for (line in block.lines) {
                        val matches = regex.find(line.text)
                        if (matches != null) {
                            Log.d("RegexMatch直播", "找到 直播：${matches.value}")
                            Log.d("OCR_Line", "位置：${line.boundingBox}")
                            val boxc = line.boundingBox
                            if (boxc != null) {
                                Full_refresh_Position_x = (boxc.centerX()).toFloat()
                                Full_refresh_Position_y = (boxc.centerY()).toFloat()
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

    @RequiresApi(Build.VERSION_CODES.N)
    private fun FindNextRoom() {
        MoveNextPage()
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun FullFreshPage() {

        if (Full_refresh_Position_x != 0f && Full_refresh_Position_y != 0f) {
            serviceScope.launch {
                delay(200L)
                TouchClick(Full_refresh_Position_x, Full_refresh_Position_y)
                //delay(1100L)
                //TouchClick(Full_refresh_Position_x, Full_refresh_Position_y)
                delay(1000L)
                MoveNextPage()
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
    private suspend fun QuickRefreshPage () {
        delay(30L)
        MoveNextPage()
        delay(1500L)
        MovePreviousPage ()
        delay(50L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun MovePreviousPage () {
        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val MoveDistance  = metrics.heightPixels / 3f
        TouchUpDown(screenCenterX,screenCenterY - MoveDistance, screenCenterY + MoveDistance, 700)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun MoveNextPage () {
        val screenCenterX = metrics.widthPixels / 2f
        val screenCenterY = metrics.heightPixels / 2f
        val MoveDistance  = metrics.heightPixels / 3f
        TouchUpDown(screenCenterX,screenCenterY + MoveDistance, screenCenterY - MoveDistance, 700)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun TouchUpDown (X: Float, Y_S: Float, Y_E: Float, MoveLong: Long) {
        val ACservice = MyAccessibilityService.instance
        ACservice?.swipe(X, Y_S , X, Y_E, MoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun TouchRightLeft (X: Float, Y_S: Float, Y_E: Float, MoveLong: Long) {
        val ACservice = MyAccessibilityService.instance
        ACservice?.swipe(X, Y_S , X, Y_E, MoveLong)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun TouchClick (X: Float, Y: Float) {
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

    private fun IsNowInTimeRangeCheck(): Boolean {
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
        stopSelf()
        Log.d("ScreenCaptureService", "App 被滑掉，服務停止")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureLoop()
        serviceJob.cancel()
        mediaProjection?.stop()
    }
}