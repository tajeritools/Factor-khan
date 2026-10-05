package ir.tajeritools.factorkhan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

data class AiInvoiceResult(
    val rawText: String,
    val items: List<InvoiceItem>,
    val printedTotal: Long?,
    val confidenceNote: String
)

class GeminiHandwritingEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("factor_khan_ai", Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString("gemini_api_key", "").orEmpty().trim()

    fun saveApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun analyzeInvoice(imageFile: File): AiInvoiceResult {
        val apiKey = getApiKey()
        require(apiKey.isNotBlank()) { "کلید Gemini API وارد نشده است." }

        val imageBase64 = encodeImage(imageFile)

        val prompt = """
تو یک سیستم تخصصی خواندن فاکتور دست‌نویس فارسی هستی.
این تصویر یک فاکتور فروش است و ممکن است متن و عددها دست‌نویس باشند.

وظیفه:
1) فقط اطلاعات قابل مشاهده را استخراج کن؛ چیزی را حدس نزن.
2) اعداد فارسی و لاتین را به عدد لاتین استاندارد تبدیل کن.
3) برای هر ردیف کالا این فیلدها را استخراج کن:
description: شرح کالا
quantity: تعداد
unit_price: قیمت واحد
row_total: مبلغ کل همان ردیف اگر در تصویر نوشته شده
confidence: عدد 0 تا 100
4) اگر قیمت به شکل 10.000.000 یا 10,000,000 نوشته شده، مقدار عددی باید 10000000 باشد.
5) اگر row_total موجود است، صحت quantity * unit_price را کنترل کن.
6) جمع کل چاپ‌شده یا دست‌نویس فاکتور را در printed_total بده؛ اگر مطمئن نیستی null.
7) transcription شامل رونویسی خوانا از متن مهم فاکتور باشد.
8) فقط JSON معتبر برگردان.

ساختار:
{
  "transcription": "...",
  "printed_total": 0,
  "confidence_note": "...",
  "items": [
    {
      "description": "...",
      "quantity": 1,
      "unit_price": 1000000,
      "row_total": 1000000,
      "confidence": 90
    }
  ]
}
""".trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageBase64)
                    ))
                    .put(JSONObject().put("text", prompt))
                )
            ))
            put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.1)
            )
        }

        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw IllegalStateException("Gemini API error " + responseCode + ": " + responseText.take(500))
        }

        val outer = JSONObject(responseText)
        val text = outer.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .removePrefix("\`\`\`json")
            .removePrefix("\`\`\`")
            .removeSuffix("\`\`\`")
            .trim()

        val json = JSONObject(text)
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<InvoiceItem>()

        for (i in 0 until itemsJson.length()) {
            val item = itemsJson.optJSONObject(i) ?: continue
            val quantity = item.optDouble("quantity", 1.0)
            val unitPrice = jsonLongOrNull(item, "unit_price") ?: continue
            val rowTotal = jsonLongOrNull(item, "row_total")
            val confidence = item.optInt("confidence", 0).coerceIn(0, 100)
            val description = item.optString("description", "کالا").ifBlank { "کالا" }

            if (quantity > 0.0 && unitPrice > 0L) {
                items += InvoiceItem(
                    description = description,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    printedRowTotal = rowTotal,
                    confidence = confidence
                )
            }
        }

        return AiInvoiceResult(
            rawText = json.optString("transcription", ""),
            items = items,
            printedTotal = jsonLongOrNull(json, "printed_total"),
            confidenceNote = json.optString("confidence_note", "تحلیل دست‌خط انجام شد.")
        )
    }

    private fun jsonLongOrNull(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.get(key)) {
            is Number -> v.toDouble().roundToLong()
            is String -> InvoiceAnalyzer.parseMoney(v)
            else -> null
        }
    }

    private fun encodeImage(file: File): String {
        val original = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("تصویر قابل خواندن نیست.")
        val maxSide = 1800
        val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height).toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true
            ).also { original.recycle() }
        } else original

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
