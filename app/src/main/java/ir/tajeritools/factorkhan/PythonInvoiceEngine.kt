package ir.tajeritools.factorkhan

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PythonInvoiceItem(
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPrice: Long,
    val currency: String,
    val rowTotal: Long,
    val confidence: Int
)

data class PythonInvoiceResult(
    val items: List<PythonInvoiceItem>,
    val grandTotalIrr: Long,
    val grandTotalToman: Long,
    val formattedTotalIrr: String,
    val formattedTotalToman: String,
    val warning: String
)

class PythonInvoiceEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("python_invoice_engine", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "").orEmpty().trim().trimEnd('/')

    fun saveEndpoint(value: String) {
        prefs.edit()
            .putString("endpoint", value.trim().trimEnd('/'))
            .putBoolean("verified", false)
            .apply()
    }

    fun configured(): Boolean = endpoint().startsWith("https://")
    fun verified(): Boolean = configured() && prefs.getBoolean("verified", false)
    private fun markVerified(value: Boolean) = prefs.edit().putBoolean("verified", value).apply()

    fun testConnection(): Boolean {
        require(configured()) { "آدرس موتور Python وارد نشده است." }
        val conn = URL(endpoint() + "/health").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val ok = code in 200..299 &&
                runCatching { JSONObject(text).optBoolean("ok", false) && JSONObject(text).optBoolean("mistral_configured", false) }
                    .getOrDefault(false)
            markVerified(ok)
            ok
        } finally {
            conn.disconnect()
        }
    }

    fun analyze(file: File): PythonInvoiceResult {
        require(configured()) { "آدرس موتور Python وارد نشده است." }
        val boundary = "----FactorKhan" + System.currentTimeMillis()
        val conn = URL(endpoint() + "/analyze").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 150000
        conn.doOutput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        conn.outputStream.use { out ->
            fun write(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"file\"; filename=\"invoice.jpg\"\r\n")
            write("Content-Type: image/jpeg\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }
            write("\r\n--$boundary--\r\n")
        }

        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            markVerified(false)
            val detail = runCatching { JSONObject(response).optString("detail") }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { "خطای موتور Python: HTTP $code" })
        }

        markVerified(true)
        return parse(JSONObject(response))
    }

    private fun parse(root: JSONObject): PythonInvoiceResult {
        val items = mutableListOf<PythonInvoiceItem>()
        val arr = root.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                items += PythonInvoiceItem(
                    description = obj.optString("description", ""),
                    quantity = obj.optDouble("quantity", 0.0),
                    unit = obj.optString("unit", ""),
                    unitPrice = obj.optLong("unit_price", 0L),
                    currency = obj.optString("currency", "IRR"),
                    rowTotal = obj.optLong("row_total", 0L),
                    confidence = obj.optInt("confidence", 0).coerceIn(0, 100)
                )
            }
        }

        return PythonInvoiceResult(
            items = items,
            grandTotalIrr = root.optLong("grand_total_irr", 0L),
            grandTotalToman = root.optLong("grand_total_toman", 0L),
            formattedTotalIrr = root.optString("formatted_total_irr", "0"),
            formattedTotalToman = root.optString("formatted_total_toman", "0"),
            warning = root.optString("warning", "")
        )
    }
}
