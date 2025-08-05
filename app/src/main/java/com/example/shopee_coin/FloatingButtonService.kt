package com.example.shopee_coin

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import kotlin.math.absoluteValue

var IsOn: Boolean = false
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var button: ImageView
    private lateinit var statusText: TextView
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FloatingButtonService = this@FloatingButtonService
    }


    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_button, null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        statusText = floatingView.findViewById(R.id.statusText)

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

    // 外部可呼叫此方法更新文字
    fun updateStatusText(text: String) {
        statusText.text = text
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}