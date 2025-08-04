package com.example.shopee_coin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null

        fun performBack() {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
                ?: Log.e("MyAccessibilityService", "Service 尚未啟動，無法執行返回操作")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 這裡可略過或根據需要處理事件
    }

    override fun onInterrupt() {
        Log.w("MyAccessibilityService", "服務被中斷")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        duration: Long = 1000L,
        callback: GestureResultCallback
    ): Boolean {
        val path = Path()
        path.moveTo(fromX, fromY)
        path.lineTo(toX, toY)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = dispatchGesture(gesture, callback, null)

        if (result) {
            Log.d("MyAccessibilityService", "已執行滑動手勢")
        } else {
            Log.e("MyAccessibilityService", "滑動手勢失敗")
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun click(
        x: Float,
        y: Float,
        callback: GestureResultCallback
        ): Boolean  {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.3f, y + 0.3f)  // 建議畫出 1px 的點擊動作
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = dispatchGesture(gesture, callback, null)
        if (result) {
            Log.d("MyAccessibilityService", "已執行點擊手勢：($x, $y)")
        } else {
            Log.e("MyAccessibilityService", "點擊手勢失敗")
        }
        return result
    }

}