package ir.tajeritools.factorkhan

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

data class MistralInvoiceAnalysis(
    val rawText: String,
    val items: List<InvoiceItem>,
    val printedTotal: Long?,
    val note: String
)

class MistralDocumentAi(private val context: Context) {
    private val prefs = context.getSharedPreferences("mistral_doc_ai", Context.MODE_PRIVATE)

    fun key(): String = prefs.getString("key", "").orEmpty().trim()
    fun saveKey(value: String) = prefs.edit().putString("key", value.trim()).apply()
    fun configured(): Boolean = key().isNotBlank()

    fun analyzeInvoice(imageFile: File): MistralInvoiceAnalysis {
        require(configured()) { "کلید Mistral وارد نشده است." }
        val bytes = imageFile.readBytes()
        require(bytes.size <= 10 * 1024 * 1024) { "حجم تصویر زیاد است." }

        val mime = when (imageFile.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val dataUrl = "data:" + mime + ";base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        val prompt = "فاکتور فارسی را تحلیل کن. فقط JSON بده. ساختار: " +
            "{items:[{description:string,quantity:number|null,unit_price:number|null,row_total:number|null,confidence:number|null}]," +
            "printed_total:number|null,note:string}. اعداد فارسی را لاتین کن. عدد ناخوانا را null بگذار و حدس نزن."

        val body = JSONObject()
            .put("model", "mistral-ocr-latest")
            .put("document", JSONObject().put("type", "image_url").put("image_url", dataUrl))
            .put("include_blocks", true)
            .put("confidence_scores_granularity", "block")
            .put("document_annotation_format", JSONObject().put("type", "json_object"))
            .put("document_annotation_prompt", prompt)

        val conn = URL("https://api.mistral.ai/v1/ocr").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer " + key())
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = conn.responseCode
        val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("Mistral HTTP " + status + ": " + response.take(350))
        return parse(JSONObject(response))
    }

    private fun parse(root: JSONObject): MistralInvoiceAnalysis {
        val pages = root.optJSONArray("pages")
        val raw = buildString {
            if (pages != null) for (i in 0 until pages.length()) {
                val md = pages.optJSONObject(i)?.optString("markdown").orEmpty()
                if (md.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(md)
                }
            }
        }

        val annValue = root.opt("document_annotation")
        val ann = when (annValue) {
            is JSONObject -> annValue
            is String -> runCatching { JSONObject(annValue) }.getOrElse { JSONObject() }
            else -> JSONObject()
        }

        val out = mutableListOf<InvoiceItem>()
        val rows = ann.optJSONArray("items")
        if (rows != null) for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val qty = number(row, "quantity")
            val unit = money(row, "unit_price")
            val total = money(row, "row_total")
            val conf = number(row, "confidence")
                ?.let { (it * 100.0).roundToLong().toInt().coerceIn(0, 100) } ?: 0
            if (qty != null && unit != null && qty > 0.0 && unit > 0L) {
                out += InvoiceItem(
                    description = row.optString("description", "").ifBlank { "کالا" },
                    quantity = qty,
                    unitPrice = unit,
                    printedRowTotal = total,
                    confidence = conf
                )
            }
        }

        return MistralInvoiceAnalysis(
            rawText = raw,
            items = out,
            printedTotal = money(ann, "printed_total"),
            note = ann.optString("note", "Mistral OCR")
        )
    }

    private fun number(obj: JSONObject, name: String): Double? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return when (val v = obj.get(name)) {
            is Number -> v.toDouble()
            is String -> InvoiceAnalyzer.parseNumber(v)
            else -> null
        }
    }

    private fun money(obj: JSONObject, name: String): Long? =
        number(obj, name)?.roundToLong()
}
