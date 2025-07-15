package com.example.shopee_coin

import android.util.Log
import java.util.Calendar

// 如果你用 ThreeTenABP，記得 import org.threeten.bp.*


object TimeLib {

    fun GetTime():Pair<Int, Int> {
        val calendar = Calendar.getInstance()  // 取得當前時間
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        Log.d("時間為", "$hour:$minute")

        return Pair(hour,minute)
    }

    fun GetDate():Pair<Int, Int> {
        val calendar = Calendar.getInstance()  // 取得當前時間

        val month = calendar.get(Calendar.MONTH) + 1  // 注意：月份從0開始，所以要 +1
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        return Pair(month,day)
    }


}