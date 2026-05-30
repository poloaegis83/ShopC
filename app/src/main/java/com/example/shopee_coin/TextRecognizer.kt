package com.example.shopee_coin

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object TextRecognizerUtil {

    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        if (recognizer == null) {
            // 💡 使用單例模式，避免重複建立客戶端造成發熱與記憶體抖動
            recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
        return recognizer!!
    }

    // 公開函式，可供全 app 使用
    fun recognizeTextFromImage(
        bitmap: Bitmap,
        onResult: (Text) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)

        Log.d("OCR_", "辨識文字 (Singleton)")

        getRecognizer().process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText)
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }
}
