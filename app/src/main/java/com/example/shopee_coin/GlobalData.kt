package com.example.shopee_coin

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object MediaProjectionHolder {
    var resultCode: Int = -1
    var resultData: Intent? = null

    fun hasPermission(): Boolean {
        return resultCode == Activity.RESULT_OK && resultData != null
    }
}

object GlobalValueHolder {
    var DownValue: Float = 0f
    var IsLowEndDevice by mutableStateOf(false)
    var StartHour: Int = 8
    var StartMinute: Int = 0
    var EndHour: Int = 0
    var EndMinute: Int = 0
    var IsTimeLimit by mutableStateOf(false)
    var appCheckRestartFeature by mutableStateOf(false)
    var isOn = false
    var notInTimeBcckToHere by mutableStateOf(false)
    var advanceSetting by mutableStateOf(false)
    var isOldCompatibilityMode by mutableStateOf(false)
    var isDebugMode by mutableStateOf(false)
    var isImageDebugMode by mutableStateOf(false)
    var debugText by mutableStateOf("")

    // 💡 響應式 Debug 資訊
    var coinValueSatisfy by mutableStateOf(0f)
    var debugCoinPosValue by mutableStateOf("Non")
    var debugGetPos by mutableStateOf("Non")
    var debugLineText by mutableStateOf("")
    var debugLineVal by mutableStateOf("")
    var debugUpValue by mutableStateOf(0f)
    var debugDownValue by mutableStateOf(0f)
    var debugPeriodInfo by mutableStateOf("")

    // 💡 滑動參數動態調整 (factor = height / MoveDistance)
    // Default values (調整為較安全的係數，避免觸發系統手勢)
    const val DEFAULT_NEXT_FACTOR = 2.5f
    const val DEFAULT_NEXT_LONG = 800L
    const val DEFAULT_PREV_FACTOR = 2.85f
    const val DEFAULT_PREV_LONG = 800L

    var nextMoveFactor by mutableStateOf(DEFAULT_NEXT_FACTOR)
    var nextMoveLong by mutableStateOf(DEFAULT_NEXT_LONG)
    var prevMoveFactor by mutableStateOf(DEFAULT_PREV_FACTOR)
    var prevMoveLong by mutableStateOf(DEFAULT_PREV_LONG)

    var isSwipeTesting by mutableStateOf(false)
    var lastAutoStopDay by mutableStateOf(-1) // 💡 記錄上次觸發領滿自動停止的日期
}

var isEnabledAcService = true
var gHeightOffset: Float = 0.0f
var gTotalHeight: Float = 0.0f
var gTotalWidth: Float = 0.0f

data class CoinClaim(
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class CoinStrategy(
    val Start_Hour: Int,
    val Start_Mins: Int,
    val End_Hour: Int,
    val End_Mins: Int,
    val PeriodUpValue: Float,
    val PeriodDownValue: Float,
    val CoinValueMinus: Float,
    val RefreshCount: Int
)

val DefaultStrategies = listOf(
    //早上
    CoinStrategy(8, 0, 11, 30, 0.3f, 0.2f, 0.1f, 5),
    //中午
    CoinStrategy(11, 10, 13, 30, 0.4f, 0.25f, 0.05f,5),
    //下午
    CoinStrategy(14, 0, 17, 40, 0.35f, 0.25f, 0.1f,5),
    //傍晚
    CoinStrategy(17, 40, 19, 30, 0.3f, 0.2f, 0.05f,5),
    //晚上
    CoinStrategy(19, 30, 23, 59, 0.4f, 0.3f, 0.1f,5),
)