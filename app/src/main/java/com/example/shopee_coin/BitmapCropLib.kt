package com.example.shopee_coin

import android.graphics.Bitmap
import android.graphics.Rect

object BitmapCropLib {

    //裁切圖片為右上角四分之一區域
    fun cropToTopRightQuarter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val croppedWidth = width / 2
        val croppedHeight = height / 2

        val rect = Rect(croppedWidth, 0, width, croppedHeight)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    fun cropToVerticalMiddleTwo(bitmap: Bitmap): Bitmap {  // 中間 1/2
        val width = bitmap.width
        val height = bitmap.height

        val top = height / 4
        val cropHeight = height / 2

        return Bitmap.createBitmap(bitmap, 0, top, width, cropHeight)
    }

    fun cropToVerticalTopQuarter(bitmap: Bitmap): Bitmap {  // 上方 1/4
        val width = bitmap.width
        val height = bitmap.height

        val top = 0
        val cropHeight = height / 4

        return Bitmap.createBitmap(bitmap, 0, top, width, cropHeight)
    }

}