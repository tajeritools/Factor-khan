package ir.tajeritools.factorkhan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
    val confidenceNote: String,
    val serverComputedTotal: Long?,
    val serverTotalVerified: Boolean?,
    val serverWarning: String
)

class OpenAiProxyEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("factor_khan_ai", Context.MODE_PRIVATE)

    fun getEndpoint(): String =
        prefs.getString(
            "openai_proxy_endpoint",
            "https://tajeritools.ir/wp-json/factorkhan/v1/analyze"
        ).orEmpty().trim()

    fun getAccessToken(): String =
        prefs.getString("openai_proxy_token", "").orEmpty().trim()

    fun saveSettings(endpoint: String, token: String) {
        prefs.edit()
            .putString("openai_proxy_endpoint", endpoint.trim())
            .putString("openai_proxy_token", token.trim())
            .apply()
    }

    fun analyzeInvoice(imageFile: File): AiInvoiceResult {
        val endpoint = getEndpoint()
        val token = getAccessToken()
        require(endpoint.startsWith("https://")) { "آدرس سرور باید HTTPS باشد." }
        require(token.isNotBlank()) { "توکن اتصال FactorKhan وارد نشده است." }

        val encoded = encodeImage(imageFile)
        val request = JSONObject()
            .put("mime_type", "image/jpeg")
            .put("image_base64", encoded)

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 100000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-FactorKhan-Token", token)
        }

        conn.outputStream.use {
            it.write(request.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            val message = runCatching {
                JSONObject(responseText).optString("message")
            }.getOrNull().orEmpty()
            throw IllegalStateException(
                if (message.isNotBlank()) message else "خطای سرور FactorKhan: $code"
            )
        }

        val json = JSONObject(responseText)
        val itemsArray = json.optJSONArray("items")
        val items = mutableListOf<InvoiceItem>()

        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                val qty = item.optDouble("quantity", 1.0)
                val unit = jsonLongOrNull(item, "unit_price") ?: continue
                val rowTotal = jsonLongOrNull(item, "row_total")
                val confidence = item.optInt("confidence", 0).coerceIn(0, 100)
                val description = item.optString("description", "کالا").ifBlank { "کالا" }

                if (qty > 0.0 && unit > 0L) {
                    items += InvoiceItem(
                        description = description,
                        quantity = qty,
                        unitPrice = unit,
                        printedRowTotal = rowTotal,
                        confidence = confidence
                    )
                }
            }
        }

        return AiInvoiceResult(
            rawText = json.optString("transcription", ""),
            items = items,
            printedTotal = jsonLongOrNull(json, "printed_total"),
            confidenceNote = json.optString("confidence_note", "تحلیل OpenAI انجام شد."),
            serverComputedTotal = jsonLongOrNull(json, "server_computed_total"),
            serverTotalVerified = if (json.has("server_total_verified") && !json.isNull("server_total_verified"))
                json.optBoolean("server_total_verified")
            else null,
            serverWarning = json.optString("server_warning", "")
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
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { original.recycle() }
        } else original

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()

        val bytes = out.toByteArray()
        require(bytes.size <= 8 * 1024 * 1024) { "حجم تصویر برای تحلیل آنلاین زیاد است." }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
