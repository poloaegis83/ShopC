package com.example.shopee_coin

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
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

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var button: ImageView
    private lateinit var statusText: TextView
    private lateinit var recordText: TextView
    private lateinit var debugText: TextView
    private val binder = LocalBinder()

    private val initX = 60
    private val initY = 1130

    inner class LocalBinder : Binder() {
        fun getService(): FloatingButtonService = this@FloatingButtonService
    }

    private val coinClaimStorage: CoinClaimStorage by lazy {
        CoinClaimStorage(this)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_button, null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        statusText = floatingView.findViewById(R.id.statusText)
        recordText = floatingView.findViewById(R.id.recordText)
        debugText = floatingView.findViewById(R.id.debugText)
        button = floatingView.findViewById(R.id.floatingButton)

        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = initX
        layoutParams.y = initY

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        // 💡 啟動時強制同步狀態：讓 isOn 變數與 Preferences 保持一致，並更新 UI
        GlobalValueHolder.isOn = false
        prefs.edit { putBoolean("OCR_ENABLED", false) }

        // 💡 載入上次自動停止的日期
        GlobalValueHolder.lastAutoStopDay = prefs.getInt("LAST_AUTO_STOP_DAY", -1)

        // 💡 確保按鈕圖示與文字正確反映初始的 false 狀態
        updateButtonUI()

        // 拖曳與點擊邏輯
        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        var newX = initialX + (event.rawX - initialTouchX).toInt()
                        var newY = initialY + (event.rawY - initialTouchY).toInt()

                        // 保證不超出螢幕
                        newX = newX.coerceIn(0, screenWidth - floatingView.width)
                        newY = newY.coerceIn(0, screenHeight - floatingView.height)

                        layoutParams.x = newX
                        layoutParams.y = newY
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val forbiddenXStart = screenWidth / 2
                        val forbiddenYEnd = screenHeight / 2.5

                        if (layoutParams.x >= forbiddenXStart && layoutParams.y <= forbiddenYEnd) {
                            layoutParams.x = initX
                            layoutParams.y = initY
                            windowManager.updateViewLayout(floatingView, layoutParams)

                            // 備份原文字
                            val originalText = statusText.text.toString()
                            updateStatusText("不要移到右上")

                            // 1 秒後吐回原文字
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (statusText.text.toString() == "不要移到右上") {
                                    updateStatusText(originalText)
                                }
                            }, 1000)
                        }

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

        windowManager.addView(floatingView, layoutParams)

        Handler(Looper.getMainLooper()).post {
            setTextSize()
        }
    }

    private fun onFloatingButtonClick() {
        GlobalValueHolder.isOn = !GlobalValueHolder.isOn
        updateButtonUI()

        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("OCR_ENABLED", GlobalValueHolder.isOn) }
        Log.d("Float Button", "Feature FB ${GlobalValueHolder.isOn}")
    }

    private fun updateButtonUI() {
        if (GlobalValueHolder.isOn) {
            updateStatusText("已開啟")
        } else {
            updateStatusText("暫停")
        }
        val resId = if (GlobalValueHolder.isOn) R.drawable.on_button else R.drawable.off_button
        button.setImageResource(resId)
    }

    fun updateOnOff(status: Boolean) {
        GlobalValueHolder.isOn = status
        updateButtonUI()
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
        val maxFontScale = 1.2f
        val fontScale = resources.configuration.fontScale
        val scaleLimit = fontScale.coerceIn(1f, maxFontScale)

        val adjustedSize1 = (statusText.textSize / fontScale) * scaleLimit
        val adjustedSize2 = (recordText.textSize / fontScale) * scaleLimit

        statusText.setTextSize(COMPLEX_UNIT_PX, adjustedSize1)
        recordText.setTextSize(COMPLEX_UNIT_PX, adjustedSize2)
    }

    fun updateStatusText(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusText.text = text
            updateDebugVisibility()
            updateBackgroundColor()
        }
    }

    private fun updateBackgroundColor() {
        if (!GlobalValueHolder.isOn) {
            floatingView.setBackgroundColor(Color.parseColor("#888888")) // 暫停時用深灰色
            return
        }

        if (ScreenCaptureService.lastState == ScreenCaptureService.CState.WAITING_COIN) {
            floatingView.setBackgroundColor(Color.LTGRAY) // WAITING_COIN 用亮灰色
        } else {
            floatingView.setBackgroundColor(Color.parseColor("#888888")) // 其餘狀態用稍微深灰色
        }
    }

    private fun updateDebugVisibility() {
        val layoutParams = floatingView.layoutParams as WindowManager.LayoutParams
        val displayMetrics = resources.displayMetrics

        if (GlobalValueHolder.isDebugMode) {
            debugText.visibility = View.VISIBLE
            debugText.text = GlobalValueHolder.debugText

            // 增加寬度以容納 Debug 資訊，上限為螢幕 1/3
            val targetWidth = (displayMetrics.widthPixels / 3.2f).toInt()
            if (layoutParams.width != targetWidth) {
                layoutParams.width = targetWidth
                windowManager.updateViewLayout(floatingView, layoutParams)
            }
        } else {
            debugText.visibility = View.GONE
            // 恢復原本寬度 (與 ImageView 寬度一致，約 68dp)
            val normalWidth = (68 * displayMetrics.density).toInt()
            if (layoutParams.width != normalWidth) {
                layoutParams.width = normalWidth
                windowManager.updateViewLayout(floatingView, layoutParams)
            }
        }
    }

    fun updateDebugInfo(text: String) {
        GlobalValueHolder.debugText = text
        Handler(Looper.getMainLooper()).post {
            if (GlobalValueHolder.isDebugMode) {
                debugText.visibility = View.VISIBLE
                debugText.text = text
            } else {
                debugText.visibility = View.GONE
            }
        }
    }

    fun resetFloatButtonLocation() {
        if (::floatingView.isInitialized) {
            val layoutParams = floatingView.layoutParams as WindowManager.LayoutParams
            // 只有位置不同才更新
            if (layoutParams.x != initX || layoutParams.y != initY) {
                layoutParams.x = initX
                layoutParams.y = initY
                windowManager.updateViewLayout(floatingView, layoutParams)
            }
        }
    }

    fun updateRecordTextToday() {
        val calendar = Calendar.getInstance()
        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
        val todayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayClaims = coinClaimStorage.getClaims().filter { it.timestamp >= todayStart }
        val todayCount = todayClaims.size
        val todayTotal = todayClaims.sumOf { it.amount }.toFloat()
        val truncatedTotal = (todayTotal * 10).toInt() / 10f

        Handler(Looper.getMainLooper()).post {
            @SuppressLint("SetTextI18n")
            recordText.text = "$todayCount 次, $truncatedTotal 元"

            // 💡 領滿 100 次自動停止邏輯 (每天僅限一次)
            if (todayCount >= 100 && GlobalValueHolder.lastAutoStopDay != currentDay && GlobalValueHolder.isOn) {
                GlobalValueHolder.lastAutoStopDay = currentDay
                updateOnOff(false)
                updateStatusText("今天已領滿")

                // 同步更新持久化狀態
                val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
                prefs.edit {
                    putBoolean("OCR_ENABLED", false)
                    putInt("LAST_AUTO_STOP_DAY", currentDay)
                }

                Log.d("FloatingButtonService", "今日領取已達 100 次，執行自動停止")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
