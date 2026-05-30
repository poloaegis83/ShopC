package com.example.shopee_coin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BitmapCropLib {

    // 裁切圖片為右上角四分之一區域
    fun cropToTopRightQuarter(bitmap: Bitmap): Bitmap {
        val croppedWidth = bitmap.width / 2
        val croppedHeight = bitmap.height / 2

        val rect = Rect(croppedWidth, 0, bitmap.width, croppedHeight)
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    fun upscaleBitmap(bitmap: Bitmap, factor: Int): Bitmap {
        val newWidth = bitmap.width * factor
        val newHeight = bitmap.height * factor
        return bitmap.scale(newWidth, newHeight)
    }

    /**
     * 💡 金色增強濾鏡：專門針對黃色/金色的蝦幣數字進行強化
     * 原理：黃色 = 高 R + 高 G + 低 B。
     * 我們透過重罰藍色通道 (-4.0)，讓白色 (R,G,B皆高) 變黑，只有純黃色能留下來。
     */
    fun toGoldEnhanced(src: Bitmap): Bitmap {
        val output = createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        // 💡 精準黃色提取矩陣：
        // 2.0R + 2.0G - 4.0B - 100
        // 白色像素會被 -4.0B 抵消變黑，只有 B 通道極低的金色會變白
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                2.0f, 2.0f, -4.0f, 0f, -100f,
                2.0f, 2.0f, -4.0f, 0f, -100f,
                2.0f, 2.0f, -4.0f, 0f, -100f,
                0f, 0f, 0f, 1f, 0f,
            )
        )

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * 💡 白色增強濾鏡：專攻白色文字（如時間、標題）
     * 原理：保留高亮像素，壓暗其餘部分
     */
    fun toWhiteEnhanced(src: Bitmap): Bitmap {
        val output = createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        // 💡 極端高門檻二值化矩陣：
        // 門檻設定在亮度約 200 (3000 / 15)。
        // 只有 RGB 總合超過 600 的像素才會顯示，其餘雜訊與彩色背景會被強制抹黑。
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                5.0f, 5.0f, 5.0f, 0f, -3000f,
                5.0f, 5.0f, 5.0f, 0f, -3000f,
                5.0f, 5.0f, 5.0f, 0f, -3000f,
                0f, 0f, 0f, 1f, 0f,
            )
        )

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * 使用 MediaStore 儲存圖片到公用 DCIM/ShopC_Debug 目錄，使其顯示在相簿中
     * 強化版本：加入 Flush 與 MediaScanner 通知以提高不同手機的相容性
     */
    fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap, prefix: String) {
        if (bitmap.isRecycled) return
        val timeStamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "${prefix}_$timeStamp.jpg"
        val relativePath = Environment.DIRECTORY_DCIM + File.separator + "ShopC_Debug"

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        try {
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    outputStream.flush() // 💡 強制沖刷，確保寫入
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }

                // 💡 手動通知系統掃描新檔案，確保相簿能立刻看到 (相容性強化)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(it.toString()), null, null)

                Log.d("BitmapCropLib", "圖片已存入相簿: ShopC_Debug/$fileName")
            }
        } catch (e: Exception) {
            Log.e("BitmapCropLib", "儲存相簿失敗: ${e.message}")
            // 寫入失敗則清理殘留條目
            uri?.let { resolver.delete(it, null, null) }
        }
    }

    fun cropToVerticalMiddleTwo(bitmap: Bitmap): Bitmap {  // 中間 1/2
        val top = bitmap.height / 4
        val cropHeight = bitmap.height / 2

        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropHeight)
    }

    fun cropToVerticalTop20percent(bitmap: Bitmap): Bitmap {  // 上方 1/5
        val cropHeight = bitmap.height / 5

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
    }

    fun cropToVerticalTopQuarter(bitmap: Bitmap): Bitmap {  // 上方 1/4
        val cropHeight = bitmap.height / 4

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
    }

    fun cropToVerticalButton25percent(bitmap: Bitmap): Bitmap {  // 下方 1/4
        val top = ((bitmap.height.toFloat()) * 0.75f).toInt()
        val cropHeight = bitmap.height / 4

        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropHeight)
    }
}
