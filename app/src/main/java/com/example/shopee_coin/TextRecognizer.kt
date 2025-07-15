package com.example.shopee_coin

//import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object TextRecognizerUtil {

    // 公開函式，可供全 app 使用
    fun recognizeTextFromImage(
        bitmap: Bitmap,
        context: Context,
        onResult: (Text) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        Log.d("OCR_", "辨識文字")

        recognizer.process(image)
            //.addOnSuccessListener { visionText ->
            //    onResult(visionText.text)
            //}
            .addOnSuccessListener { visionText ->
                onResult(visionText)
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }
}