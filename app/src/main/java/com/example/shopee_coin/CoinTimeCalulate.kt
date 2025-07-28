package com.example.shopee_coin

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.reflect.TypeToken
import com.google.gson.Gson

class CoinClaimStorage(context: Context) {

    private val prefs = context.getSharedPreferences("my_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()
    private val key = "coin_claims"

    // 讀取紀錄
    fun getClaims(): MutableList<CoinClaim> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<CoinClaim>>() {}.type
        return gson.fromJson(json, type)
    }

    // 儲存紀錄
    fun saveClaims(claims: List<CoinClaim>) {
        val json = gson.toJson(claims)
        prefs.edit { putString(key, json) }
    }

    // 新增一筆領取紀錄
    fun addClaim(claim: CoinClaim) {
        val current = getClaims()
        current.add(claim)
        saveClaims(current)
    }

    // 清除所有紀錄
    fun clearClaims() {
        prefs.edit { remove(key) }
    }
}