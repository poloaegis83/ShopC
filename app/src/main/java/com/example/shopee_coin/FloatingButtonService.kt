package com.example.shopee_coin

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import java.util.Calendar
import kotlin.math.absoluteValue

var IsOn: Boolean = false
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var button: ImageView
    private lateinit var statusText: TextView
    private lateinit var recordText: TextView
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FloatingButtonService = this@FloatingButtonService
    }

    // 加這行
    private val coinClaimStorage: CoinClaimStorage by lazy {
        CoinClaimStorage(this) // 或你的實作方式
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_button, null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        statusText = floatingView.findViewById(R.id.statusText)
        recordText = floatingView.findViewById<TextView>(R.id.recordText)

        //val widthPx = 150
        //val heightPx = 120

        // 載入懸浮按鈕佈局
        button = floatingView.findViewById<ImageView>(R.id.floatingButton)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,//widthPx,//WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,//heightPx,//WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 1100

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("OCR_ENABLED", false) }

        // 拖曳與點擊邏輯

        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        // 點擊觸發行為
                        if ((event.rawX - initialTouchX).absoluteValue < 10 &&
                            (event.rawY - initialTouchY).absoluteValue < 10
                        ) {
                            onFloatingButtonClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        // 加入懸浮窗
        windowManager.addView(floatingView, layoutParams)

        // 確保在主執行緒執行字體設定
        Handler(Looper.getMainLooper()).post {
            setTextSize()
        }
    }

    //private var isOn = false

    private fun onFloatingButtonClick() {

        // 切換狀態
        IsOn = !IsOn

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("OCR_ENABLED", IsOn) }
        Log.d("Float Button", "Feature FB ${IsOn}")
        // 根據狀態更換圖片
        val resId = if (IsOn) R.drawable.on_button else R.drawable.off_button
        button.setImageResource(resId)

        // 你可以在這裡控制功能開關，例如啟用 OCR 辨識流程
        //Toast.makeText(this, "懸浮按鈕被點擊了", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
        Log.d("FloatingButtonService", "App 被滑掉，服務停止")
    }

    private fun setTextSize() {
        // 限制字體大小，不受系統字體過大影響
        val maxFontScale = 1.2f
        val fontScale = resources.configuration.fontScale

        val scaleLimit = when {
            fontScale > maxFontScale -> maxFontScale
            fontScale < 1f -> 1f
            else -> fontScale
        }
        val adjustedSize1 = statusText.textSize / fontScale * scaleLimit
        val adjustedSize2 = recordText.textSize / fontScale * scaleLimit
        Log.d("setStatusTextSize", "maxFontScale = $maxFontScale, fontScale = $fontScale, adjustedSize = $adjustedSize1")

        statusText.setTextSize(COMPLEX_UNIT_PX, adjustedSize1)
        recordText.setTextSize(COMPLEX_UNIT_PX, adjustedSize2)
    }

    fun updateStatusText(text: String) {
        // 外部可呼叫此方法更新文字
        Handler(Looper.getMainLooper()).post {
            statusText.text = text
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateRecordText(times: Int, sum: Float) {
        // 外部可呼叫此方法更新文字
        Handler(Looper.getMainLooper()).post {
            recordText.text = "${times}次,共:${sum}"
        }
    }

    fun updateRecordTextToday() {
        // 先把最新的 coinClaim 加入 storage
        // coinClaimStorage.addClaim(...) 可以在這裡做

        // 計算今天統計
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayClaims = coinClaimStorage.getClaims().filter { it.timestamp >= todayStart }
        val todayCount = todayClaims.size
        val todayTotal = todayClaims.sumOf { it.amount }.toFloat()
        // 無條件捨去到小數點一位
        val truncatedTotal = (todayTotal * 10).toInt() / 10f
        // 更新懸浮按鈕
        Handler(Looper.getMainLooper()).post {
            recordText.text = "${todayCount}次, ${truncatedTotal}元"
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}