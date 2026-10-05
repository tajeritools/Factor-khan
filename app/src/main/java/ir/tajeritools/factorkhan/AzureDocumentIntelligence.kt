package ir.tajeritools.factorkhan

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AzureInvoiceAnalysis(
    val rawText: String,
    val items: List<InvoiceItem>,
    val printedTotal: Long?,
    val confidenceNote: String
)

class AzureDocumentIntelligence(private val context: Context) {
    private val prefs = context.getSharedPreferences("azure_doc_intel", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "").orEmpty().trim().trimEnd('/')
    fun key(): String = prefs.getString("key", "").orEmpty().trim()

    fun save(endpoint: String, key: String) {
        prefs.edit()
            .putString("endpoint", endpoint.trim().trimEnd('/'))
            .putString("key", key.trim())
            .apply()
    }

    fun configured(): Boolean = endpoint().startsWith("https://") && key().isNotBlank()

    fun analyzeInvoice(imageFile: File): AzureInvoiceAnalysis {
        require(configured()) { "Endpoint و Key سرویس Azure وارد نشده است." }

        val bytes = imageFile.readBytes()
        val body = JSONObject()
            .put("base64Source", Base64.encodeToString(bytes, Base64.NO_WRAP))

        val postUrl = endpoint() +
            "/documentintelligence/documentModels/prebuilt-invoice:analyze?api-version=2024-11-30"

        val post = open(postUrl, "POST")
        post.setRequestProperty("Content-Type", "application/json")
        post.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = post.responseCode
        if (code !in 200..299) {
            val err = post.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Azure POST $code: " + err.take(500))
        }

        val operation = post.getHeaderField("Operation-Location")
            ?: throw IllegalStateException("Azure آدرس نتیجه را برنگرداند.")

        repeat(30) {
            Thread.sleep(1200)
            val get = open(operation, "GET")
            val rc = get.responseCode
            val text = (if (rc in 200..299) get.inputStream else get.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (rc !in 200..299) throw IllegalStateException("Azure GET $rc: " + text.take(500))

            val json = JSONObject(text)
            when (json.optString("status")) {
                "succeeded" -> return parse(json)
                "failed" -> throw IllegalStateException(
                    json.optJSONObject("error")?.optString("message")
                        ?: "تحلیل Azure ناموفق بود."
                )
            }
        }

        throw IllegalStateException("پاسخ Azure بیش از حد طول کشید.")
    }

    private fun parse(root: JSONObject): AzureInvoiceAnalysis {
        val result = root.optJSONObject("analyzeResult") ?: JSONObject()
        val rawText = result.optString("content", "")
        val documents = result.optJSONArray("documents")
        val items = mutableListOf<InvoiceItem>()
        var printedTotal: Long? = null
        var confidenceSum = 0.0
        var confidenceCount = 0

        if (documents != null && documents.length() > 0) {
            val doc = documents.optJSONObject(0)
            val fields = doc?.optJSONObject("fields")
            printedTotal = fieldMoney(fields?.optJSONObject("InvoiceTotal"))
                ?: fieldMoney(fields?.optJSONObject("AmountDue"))

            val itemsField = fields?.optJSONObject("Items")
            val arr = itemsField?.optJSONArray("valueArray")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)?.optJSONObject("valueObject") ?: continue
                    val q = fieldNumber(obj.optJSONObject("Quantity")) ?: 1.0
                    val unit = fieldMoney(obj.optJSONObject("UnitPrice"))
                    val amount = fieldMoney(obj.optJSONObject("Amount"))
                    val desc = fieldText(obj.optJSONObject("Description")).ifBlank { "کالا" }

                    if (unit != null && unit > 0L && q > 0.0) {
                        val confs = listOfNotNull(
                            fieldConfidence(obj.optJSONObject("Quantity")),
                            fieldConfidence(obj.optJSONObject("UnitPrice")),
                            fieldConfidence(obj.optJSONObject("Amount"))
                        )
                        val conf = if (confs.isEmpty()) 0 else (confs.average() * 100.0).toInt().coerceIn(0, 100)
                        confidenceSum += conf
                        confidenceCount++

                        items += InvoiceItem(
                            description = desc,
                            quantity = q,
                            unitPrice = unit,
                            printedRowTotal = amount,
                            confidence = conf
                        )
                    }
                }
            }
        }

        val note = if (confidenceCount == 0) {
            "Azure فاکتور را تحلیل کرد، اما ردیف کامل عددی پیدا نشد."
        } else {
            "میانگین اطمینان ردیف‌ها: " + (confidenceSum / confidenceCount).toInt() + "%"
        }

        return AzureInvoiceAnalysis(rawText, items, printedTotal, note)
    }

    private fun fieldText(field: JSONObject?): String {
        if (field == null) return ""
        return field.optString("valueString", field.optString("content", ""))
    }

    private fun fieldNumber(field: JSONObject?): Double? {
        if (field == null) return null
        if (field.has("valueNumber")) return field.optDouble("valueNumber")
        val content = field.optString("content", "")
        return InvoiceAnalyzer.parseNumber(content)
    }

    private fun fieldMoney(field: JSONObject?): Long? {
        if (field == null) return null
        val currency = field.optJSONObject("valueCurrency")
        if (currency != null && currency.has("amount")) {
            return currency.optDouble("amount").toLong()
        }
        if (field.has("valueNumber")) return field.optDouble("valueNumber").toLong()
        return InvoiceAnalyzer.parseMoney(field.optString("content", ""))
    }

    private fun fieldConfidence(field: JSONObject?): Double? =
        field?.takeIf { it.has("confidence") }?.optDouble("confidence")

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30000
            readTimeout = 90000
            setRequestProperty("Ocp-Apim-Subscription-Key", key())
            setRequestProperty("Accept", "application/json")
            if (method == "POST") doOutput = true
        }
}
