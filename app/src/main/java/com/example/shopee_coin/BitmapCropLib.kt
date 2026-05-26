package com.example.shopee_coin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.set
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 💡 金色增強濾鏡：專門針對黃色/金色的蝦幣數字進行強化
     * 原理：黃色 = 高 R + 高 G + 低 B。
     * 我們透過重罰藍色通道 (-4.0)，讓白色 (R,G,B皆高) 變黑，只有純黃色能留下來。
     */
    fun toGoldEnhanced(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        // 💡 精準黃色提取矩陣：
        // 2.0R + 2.0G - 4.0B - 100
        // 白色像素會被 -4.0B 抵消變黑，只有 B 通道極低的金色會變白
        val colorMatrix = ColorMatrix(floatArrayOf(
            2.0f, 2.0f, -4.0f, 0f, -100f,
            2.0f, 2.0f, -4.0f, 0f, -100f,
            2.0f, 2.0f, -4.0f, 0f, -100f,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * 白色增強濾鏡：專攻白色文字（如時間、標題）
     * 原理：保留高亮像素，壓暗其餘部分
     */
    fun toWhiteEnhanced(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        // 💡 極端高門檻二值化矩陣：
        // 門檻設定在亮度約 200 (3000 / 15)。
        // 只有 RGB 總合超過 600 的像素才會顯示，其餘雜訊與彩色背景會被強制抹黑。
        val colorMatrix = ColorMatrix(floatArrayOf(
            5.0f, 5.0f, 5.0f, 0f, -3000f,
            5.0f, 5.0f, 5.0f, 0f, -3000f,
            5.0f, 5.0f, 5.0f, 0f, -3000f,
            0f, 0f, 0f, 1f, 0f
        ))

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

    /**
     * 儲存圖片到 App 專屬目錄用於 Debug (備用)
     */
    fun saveBitmapToDebugDir(context: android.content.Context, bitmap: Bitmap, prefix: String) {
        if (bitmap.isRecycled) return
        try {
            val debugDir = context.getExternalFilesDir("DebugImages")
            if (debugDir != null && !debugDir.exists()) debugDir.mkdirs()
            
            val timeStamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = "${prefix}_$timeStamp.jpg"
            val file = File(debugDir, fileName)

            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.close()
            Log.d("BitmapCropLib", "圖片已儲存至: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("BitmapCropLib", "儲存圖片失敗: ${e.message}")
        }
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

    fun cropToVerticalButton25percent(bitmap: Bitmap): Bitmap {  // 下方 1/4
        val width = bitmap.width
        val height = bitmap.height

        val top = ((height.toFloat()) * 0.75f).toInt()
        val cropHeight = height / 4

        return Bitmap.createBitmap(bitmap, 0, top, width, cropHeight)
    }

}