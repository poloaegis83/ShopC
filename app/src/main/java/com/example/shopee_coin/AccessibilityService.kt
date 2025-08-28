package com.example.shopee_coin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
        @Volatile
        var isRunning = false
        fun performBack() {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
                ?: Log.e("MyAccessibilityService", "Service 尚未啟動，無法執行返回操作")
        }

        fun performHome() {
            instance?.performGlobalAction(GLOBAL_ACTION_HOME)
                ?: Log.e("MyAccessibilityService", "Service 尚未啟動，無法執行home操作")
        }

        // 紀錄目前前景的 App
        @Volatile
        var currentForegroundApp: String? = null

        private const val TARGET_APP = "com.shopee.tw"   // 預期的 App (hardcode)
        private const val TARGET_MY_APP = "com.meteor.alderlake"   // 預期的 App (hardcode)

        // 對外提供檢查 API
        fun checkForegroundApp(): Boolean {
            Log.d("MyAccessibilityService", "currentForegroundApp = $currentForegroundApp")
            return currentForegroundApp == TARGET_APP
        }

        fun checkForegroundMyApp(): Boolean {
            Log.d("MyAccessibilityService", "checkForegroundMyApp = $currentForegroundApp")
            return currentForegroundApp == TARGET_MY_APP
        }

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("MyAccessibilityService", "服務已啟動")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("MyAccessibilityService", "服務已停止")
    }

    //override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 這裡可略過或根據需要處理事件
    //}

    override fun onInterrupt() {
        Log.w("MyAccessibilityService", "服務被中斷")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d("AccessibilityService", "onServiceConnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        Log.d("AccessibilityService", "onUnbind")
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, duration: Long = 1000L) {
        val path = Path()
        path.moveTo(fromX, fromY)
        path.lineTo(toX, toY)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
        Log.d("MyAccessibilityService", "已執行滑動手勢")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun swipeBezier(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        duration: Long = 1000L
    ) {
        val path = Path().apply {
            moveTo(fromX, fromY)

            // 生成兩個隨機控制點，增加自然度
            val controlX1 = fromX + (toX - fromX) * 0.3f + (-60..60).random()
            val controlY1 = fromY + (toY - fromY) * 0.3f + (-60..60).random()

            val controlX2 = fromX + (toX - fromX) * 0.6f + (-60..60).random()
            val controlY2 = fromY + (toY - fromY) * 0.6f + (-60..60).random()

            // 三階貝茲曲線
            cubicTo(controlX1, controlY1, controlX2, controlY2, toX, toY)
        }

        // duration 基本值 + 隨機 0–50ms
        val finalDuration = duration + (0..50).random()

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
        Log.d("MyAccessibilityService", "已執行貝茲曲線滑動")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun click(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.3f, y + 0.3f)  // 建議畫出 1px 的點擊動作
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = dispatchGesture(gesture, null, null)
        if (result) {
            Log.d("MyAccessibilityService", "已執行點擊手勢：($x, $y)")
        } else {
            Log.e("MyAccessibilityService", "點擊手勢失敗")
        }
    }

  //com.shopee.tw
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
      if (event == null) return

      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
          val pkg = event.packageName?.toString()
          if (!pkg.isNullOrEmpty()) {
              currentForegroundApp = pkg
              Log.d("MyAccessibilityService", "Foreground changed = $pkg")
          }
      }
  }

}