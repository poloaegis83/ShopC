package com.example.shopee_coin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.set

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

    fun upscaleBitmap(bitmap: Bitmap, factor: Int): Bitmap {
        val newWidth = bitmap.width * factor
        val newHeight = bitmap.height * factor
        return bitmap.scale(newWidth, newHeight)
    }

    fun toGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val bmpGrayscale = createBitmap(width, height)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f) // 設為 0 表示灰階
        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmpGrayscale
    }

    fun toBinary(src: Bitmap, threshold: Int = 128): Bitmap {
        val width = src.width
        val height = src.height
        val binarized = createBitmap(width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = src[x, y]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (r + g + b) / 3
                val newColor = if (gray < threshold) Color.BLACK else Color.WHITE
                binarized[x, y] = newColor
            }
        }

        return binarized
    }

    /*
    📐 範例輸出尺寸 (原圖 1000x1000)：
    原圖右上 1/4：500x500

    再取右半：250x500

    去掉上 1/4：250x375
   */
    fun cropFinalRegion(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Step 1: 取右上 1/4
        val quarterLeft = width / 2
        val quarterTop = 0
        val quarterRight = width
        val quarterBottom = height / 2

        // Step 2: 對這個 1/4 再切 X 軸一半（取右半邊）
        val eighthLeft = quarterLeft + (quarterRight - quarterLeft) / 2
        val eighthRight = quarterRight

        // Step 3: 裁掉最上方 1/4 的高度
        val eighthTop = (quarterBottom - quarterTop) / 4 // 這是 1/4 高度
        val eighthBottom = quarterBottom

        // 計算裁切範圍
        val rect = Rect(eighthLeft, eighthTop, eighthRight, eighthBottom)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    //裁切圖片為右上角四分之一區域
    //再 X軸 1/2 (1/8)
    fun cropToTopRightEighth(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        Log.d("cropToTopRightEighth", "width：${bitmap.width}  height：${bitmap.height}")

        // 先取得右上 1/4 的區域
        val quarterLeft = width / 2
        val quarterTop = 0
        val quarterRight = width
        val quarterBottom = height / 2

        // 然後從這塊再切一半（X方向），只要右半邊（1/8）
        val eighthLeft = quarterLeft + (quarterRight - quarterLeft) / 2
        val eighthTop = quarterTop
        val eighthRight = quarterRight
        val eighthBottom = quarterBottom

        val rect = Rect(eighthLeft, eighthTop, eighthRight, eighthBottom)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    fun cropToVerticalMiddleTwo(bitmap: Bitmap): Bitmap {  // 中間 1/2
        val width = bitmap.width
        val height = bitmap.height

        val top = height / 4
        val cropHeight = height / 2

        return Bitmap.createBitmap(bitmap, 0, top, width, cropHeight)
    }

    fun cropToVerticalTop20percent(bitmap: Bitmap): Bitmap {  // 上方 1/5
        val width = bitmap.width
        val height = bitmap.height

        val top = 0
        val cropHeight = height / 5

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