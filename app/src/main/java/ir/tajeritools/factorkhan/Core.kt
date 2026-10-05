package ir.tajeritools.factorkhan

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.titleText(value: String, size: Float = 22f): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(Color.rgb(28, 28, 28))
    gravity = Gravity.RIGHT
    setPadding(dp(4), dp(8), dp(4), dp(8))
}

fun Context.actionButton(value: String): Button = Button(this).apply {
    text = value
    isAllCaps = false
}

fun Context.verticalRoot(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
    layoutDirection = View.LAYOUT_DIRECTION_RTL
}

fun money(v: Long?): String =
    if (v == null) "—" else String.format(Locale.US, "%,d", v).replace(",", ".")

fun qtyText(v: Double): String =
    if (abs(v - v.toLong()) < 0.0001) v.toLong().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

data class Customer(
    val id: Long,
    val name: String,
    val phone: String,
    val notes: String,
    val createdAt: Long
)

data class InvoiceRecord(
    val id: Long,
    val customerId: Long,
    val title: String,
    val imagePath: String,
    val ocrText: String,
    val computedTotal: Long?,
    val reportedTotal: Long?,
    val statusMessage: String,
    val createdAt: Long
)

data class InvoiceItem(
    val id: Long = 0,
    val invoiceId: Long = 0,
    val description: String,
    val quantity: Double,
    val unitPrice: Long,
    val printedRowTotal: Long? = null,
    val confidence: Int = 0
) {
    fun computedRowTotal(): Long = (quantity * unitPrice.toDouble()).roundToLong()
}

class DbHelper(context: Context) : SQLiteOpenHelper(context, "factor_khan.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBaseTables(db)
        createItemTable(db)
    }

    private fun createBaseTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE customers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE invoices(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                image_path TEXT NOT NULL DEFAULT '',
                ocr_text TEXT NOT NULL DEFAULT '',
                computed_total INTEGER,
                reported_total INTEGER,
                status_message TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_invoices_customer ON invoices(customer_id, created_at DESC)")
    }

    private fun createItemTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS invoice_items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                quantity REAL NOT NULL DEFAULT 1,
                unit_price INTEGER NOT NULL DEFAULT 0,
                printed_row_total INTEGER,
                confidence INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_invoice_items_invoice ON invoice_items(invoice_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createItemTable(db)
    }

    fun addCustomer(name: String, phone: String, notes: String): Long {
        val v = ContentValues().apply {
            put("name", name.trim())
            put("phone", phone.trim())
            put("notes", notes.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("customers", null, v)
    }

    fun updateCustomer(id: Long, name: String, phone: String, notes: String) {
        val v = ContentValues().apply {
            put("name", name.trim())
            put("phone", phone.trim())
            put("notes", notes.trim())
        }
        writableDatabase.update("customers", v, "id=?", arrayOf(id.toString()))
    }

    fun customers(): List<Customer> {
        val out = mutableListOf<Customer>()
        readableDatabase.rawQuery(
            "SELECT id,name,phone,notes,created_at FROM customers ORDER BY name COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext()) out += Customer(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)
            )
        }
        return out
    }

    fun customer(id: Long): Customer? {
        readableDatabase.rawQuery(
            "SELECT id,name,phone,notes,created_at FROM customers WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) Customer(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)
            ) else null
        }
    }

    fun deleteCustomer(id: Long) {
        writableDatabase.delete("customers", "id=?", arrayOf(id.toString()))
    }

    fun invoices(customerId: Long): List<InvoiceRecord> {
        val out = mutableListOf<InvoiceRecord>()
        readableDatabase.rawQuery(
            """SELECT id,customer_id,title,image_path,ocr_text,computed_total,reported_total,status_message,created_at
               FROM invoices WHERE customer_id=? ORDER BY created_at DESC""",
            arrayOf(customerId.toString())
        ).use { c -> while (c.moveToNext()) out += c.toInvoice() }
        return out
    }

    fun invoice(id: Long): InvoiceRecord? {
        readableDatabase.rawQuery(
            """SELECT id,customer_id,title,image_path,ocr_text,computed_total,reported_total,status_message,created_at
               FROM invoices WHERE id=?""",
            arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) c.toInvoice() else null }
    }

    fun invoiceItems(invoiceId: Long): List<InvoiceItem> {
        val out = mutableListOf<InvoiceItem>()
        readableDatabase.rawQuery(
            """SELECT id,invoice_id,description,quantity,unit_price,printed_row_total,confidence
               FROM invoice_items WHERE invoice_id=? ORDER BY id""",
            arrayOf(invoiceId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += InvoiceItem(
                    id = c.getLong(0),
                    invoiceId = c.getLong(1),
                    description = c.getString(2),
                    quantity = c.getDouble(3),
                    unitPrice = c.getLong(4),
                    printedRowTotal = if (c.isNull(5)) null else c.getLong(5),
                    confidence = c.getInt(6)
                )
            }
        }
        return out
    }

    fun saveInvoice(
        id: Long?,
        customerId: Long,
        title: String,
        imagePath: String,
        ocrText: String,
        computedTotal: Long?,
        reportedTotal: Long?,
        statusMessage: String,
        items: List<InvoiceItem> = emptyList()
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("customer_id", customerId)
                put("title", title.ifBlank { "فاکتور" })
                put("image_path", imagePath)
                put("ocr_text", ocrText)
                if (computedTotal == null) putNull("computed_total") else put("computed_total", computedTotal)
                if (reportedTotal == null) putNull("reported_total") else put("reported_total", reportedTotal)
                put("status_message", statusMessage)
                put("created_at", System.currentTimeMillis())
            }
            val invoiceId = if (id == null || id <= 0L) {
                db.insertOrThrow("invoices", null, v)
            } else {
                db.update("invoices", v, "id=?", arrayOf(id.toString()))
                id
            }

            db.delete("invoice_items", "invoice_id=?", arrayOf(invoiceId.toString()))
            items.forEach { item ->
                val iv = ContentValues().apply {
                    put("invoice_id", invoiceId)
                    put("description", item.description.trim())
                    put("quantity", item.quantity)
                    put("unit_price", item.unitPrice)
                    if (item.printedRowTotal == null) putNull("printed_row_total")
                    else put("printed_row_total", item.printedRowTotal)
                    put("confidence", item.confidence)
                }
                db.insertOrThrow("invoice_items", null, iv)
            }

            db.setTransactionSuccessful()
            return invoiceId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteInvoice(id: Long) {
        val inv = invoice(id)
        writableDatabase.delete("invoices", "id=?", arrayOf(id.toString()))
        val path = inv?.imagePath.orEmpty()
        if (path.isNotBlank()) runCatching { File(path).delete() }
    }

    private fun Cursor.toInvoice() = InvoiceRecord(
        id = getLong(0),
        customerId = getLong(1),
        title = getString(2),
        imagePath = getString(3),
        ocrText = getString(4),
        computedTotal = if (isNull(5)) null else getLong(5),
        reportedTotal = if (isNull(6)) null else getLong(6),
        statusMessage = getString(7),
        createdAt = getLong(8)
    )
}

data class AnalysisResult(
    val subtotal: Long,
    val tax: Long,
    val discount: Long,
    val expectedFinal: Long,
    val reportedTotal: Long?,
    val difference: Long?,
    val warning: String,
    val usedLines: List<String>
)

object InvoiceAnalyzer {
    private val totalWords = listOf("جمع کل", "قابل پرداخت", "مبلغ نهایی", "جمع نهایی", "grand total", "amount due", "total")
    private val taxWords = listOf("مالیات", "ارزش افزوده", "مالیات بر ارزش افزوده", "vat", "tax")
    private val discountWords = listOf("تخفیف", "discount")
    private val ignoreWords = listOf(
        "شماره فاکتور", "تلفن", "موبایل", "تاریخ", "کد ملی", "شناسه",
        "invoice no", "phone", "date", "s.no", "price", "unity price", "qty", "description"
    )
    private val numberRegex = Regex("""[-+]?\d[\d,٬،.]*""")

    fun normalizeDigits(input: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder(input.length)
        input.forEach { ch ->
            val fi = fa.indexOf(ch)
            val ai = ar.indexOf(ch)
            sb.append(
                when {
                    fi >= 0 -> ('0'.code + fi).toChar()
                    ai >= 0 -> ('0'.code + ai).toChar()
                    ch == '٬' || ch == '،' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun parseNumber(text: String): Double? {
        var normalized = normalizeDigits(text)
            .replace(" ", "")
            .replace(",", "")
            .trim()

        if (normalized.isBlank()) return null

        val dotCount = normalized.count { it == '.' }
        normalized = when {
            dotCount > 1 -> normalized.replace(".", "")
            dotCount == 1 -> {
                val before = normalized.substringBefore(".").replace("+", "").replace("-", "")
                val after = normalized.substringAfter(".")
                if (after.length == 3 && before.isNotBlank()) normalized.replace(".", "") else normalized
            }
            else -> normalized
        }

        return normalized.toDoubleOrNull()
    }

    fun parseMoney(text: String): Long? = parseNumber(text)?.roundToLong()

    fun extractItems(raw: String): List<InvoiceItem> {
        val lines = normalizeDigits(raw).lines().map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<InvoiceItem>()

        lines.forEach { line ->
            val lower = line.lowercase()
            if ((totalWords + taxWords + discountWords + ignoreWords).any { lower.contains(it.lowercase()) }) return@forEach

            val matches = numberRegex.findAll(line).toList()
            val values = matches.mapNotNull { parseNumber(it.value) }
            if (values.size < 2) return@forEach

            val description = numberRegex.replace(line, " ")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', ':', '|', '،', ',')

            var best: InvoiceItem? = null
            var bestScore = Double.MAX_VALUE

            if (values.size >= 3) {
                val tail = values.takeLast(minOf(4, values.size))
                for (qi in tail.indices) {
                    for (ui in tail.indices) {
                        if (ui == qi) continue
                        for (ti in tail.indices) {
                            if (ti == qi || ti == ui) continue
                            val q = tail[qi]
                            val u = tail[ui]
                            val t = tail[ti]
                            if (q <= 0.0 || q > 10000.0 || u <= 0.0 || t <= 0.0) continue
                            val calc = q * u
                            val rel = abs(calc - t) / max(1.0, t)
                            val qtyPenalty = if (q <= 500.0) 0.0 else 0.4
                            val score = rel + qtyPenalty
                            if (score < bestScore) {
                                bestScore = score
                                best = InvoiceItem(
                                    description = description.ifBlank { "کالا" },
                                    quantity = q,
                                    unitPrice = u.roundToLong(),
                                    printedRowTotal = t.roundToLong(),
                                    confidence = when {
                                        rel <= 0.02 -> 95
                                        rel <= 0.08 -> 80
                                        rel <= 0.20 -> 60
                                        else -> 40
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (best == null) {
                val a = values[values.size - 2]
                val b = values.last()
                val (q, u) = if (a <= 500.0 && b > a) a to b else 1.0 to b
                best = InvoiceItem(
                    description = description.ifBlank { "کالا" },
                    quantity = q,
                    unitPrice = u.roundToLong(),
                    printedRowTotal = null,
                    confidence = if (values.size >= 2) 45 else 25
                )
            }

            if (best!!.unitPrice > 0L && best!!.quantity > 0.0) out += best!!
        }

        return dedupeItems(out)
    }

    private fun dedupeItems(items: List<InvoiceItem>): List<InvoiceItem> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<InvoiceItem>()
        items.forEach { item ->
            val key = item.description.lowercase().replace(" ", "") + "|" +
                qtyText(item.quantity) + "|" + item.unitPrice
            if (seen.add(key)) result += item
        }
        return result
    }

    fun analyze(
        raw: String,
        manualReported: Long? = null,
        structuredItems: List<InvoiceItem> = emptyList()
    ): AnalysisResult {
        val lines = normalizeDigits(raw).lines().map { it.trim() }.filter { it.isNotBlank() }

        fun lastAmount(line: String): Long? =
            numberRegex.findAll(line).mapNotNull { parseMoney(it.value) }.toList().lastOrNull()

        fun findKeywordAmount(words: List<String>): Long? =
            lines.firstNotNullOfOrNull { line ->
                if (words.any { line.contains(it, ignoreCase = true) }) lastAmount(line) else null
            }

        val reported = manualReported ?: findKeywordAmount(totalWords)
        val tax = findKeywordAmount(taxWords) ?: 0L
        val discount = findKeywordAmount(discountWords) ?: 0L
        val used = mutableListOf<String>()

        val subtotal = if (structuredItems.isNotEmpty()) {
            structuredItems.sumOf { it.computedRowTotal() }
        } else {
            var s = 0L
            for (line in lines) {
                val lower = line.lowercase()
                if ((totalWords + taxWords + discountWords + ignoreWords).any { lower.contains(it.lowercase()) }) continue
                val nums = numberRegex.findAll(line).mapNotNull { parseMoney(it.value) }.filter { it >= 0 }.toList()
                if (nums.isEmpty()) continue
                val hasLetters = line.any { it.isLetter() }
                val likelyRow = nums.size >= 2 || (hasLetters && nums.last() >= 1000)
                if (!likelyRow) continue
                val rowTotal = nums.last()
                if (rowTotal <= 0L) continue
                s += rowTotal
                used += line
            }
            s
        }

        val expected = (subtotal + tax - discount).coerceAtLeast(0)
        val diff = reported?.let { expected - it }
        val tolerance = reported?.let { max(1000L, (it * 0.005).toLong()) } ?: 1000L

        val rowMismatch = structuredItems.count { item ->
            val printed = item.printedRowTotal ?: return@count false
            abs(item.computedRowTotal() - printed) > max(1000L, (printed * 0.005).toLong())
        }

        val warning = when {
            structuredItems.isNotEmpty() && rowMismatch > 0 ->
                "⚠ در $rowMismatch ردیف، تعداد × قیمت واحد با مبلغ ردیف نمی‌خواند. ردیف‌های علامت‌دار را بررسی کنید."
            reported == null ->
                "جمع کل چاپ‌شده پیدا نشد؛ اگر روی فاکتور هست آن را دستی وارد کنید."
            structuredItems.isEmpty() && used.isEmpty() ->
                "ردیف‌های فاکتور با اطمینان کافی تشخیص داده نشدند؛ متن OCR یا اقلام را بررسی کنید."
            abs(diff ?: 0L) <= tolerance ->
                "✓ جمع فاکتور با اقلام تشخیص‌داده‌شده هم‌خوان است."
            else ->
                "⚠ اختلاف " + money(abs(diff!!)) + " بین محاسبه اقلام و جمع کل دیده شد. فاکتور را بررسی کنید."
        }

        return AnalysisResult(subtotal, tax, discount, expected, reported, diff, warning, used)
    }
}

data class OcrResult(val text: String, val confidence: Int)

class OcrEngine(private val context: Context) {
    private val root = File(context.filesDir, "tesseract")
    private val tessdata = File(root, "tessdata")

    fun recognize(imageFile: File): OcrResult {
        ensureModels()
        val bitmap = loadForOcr(imageFile)
        val passes = listOf(
            TessBaseAPI.PageSegMode.PSM_AUTO,
            TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        )

        var bestText = ""
        var bestConfidence = -1

        for (mode in passes) {
            val api = TessBaseAPI()
            try {
                val ok = api.init(root.absolutePath, "fas+eng")
                if (!ok) error("راه‌اندازی OCR ناموفق بود.")
                api.setPageSegMode(mode)
                api.setVariable("preserve_interword_spaces", "1")
                api.setImage(bitmap)
                val text = api.getUTF8Text().orEmpty()
                val confidence = api.meanConfidence()
                if (confidence > bestConfidence && text.count { it.isDigit() } >= bestText.count { it.isDigit() } / 2) {
                    bestText = text
                    bestConfidence = confidence
                } else if (text.count { it.isDigit() } > bestText.count { it.isDigit() } + 4) {
                    bestText = text
                    bestConfidence = confidence
                }
            } finally {
                api.recycle()
            }
        }

        // یک پاس جداگانه برای اعداد و قیمت‌ها؛ مخصوص فاکتورهای دست‌نویس/کم‌رنگ.
        runCatching {
            val numericApi = TessBaseAPI()
            try {
                if (numericApi.init(root.absolutePath, "fas+eng")) {
                    numericApi.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                    numericApi.setVariable("tessedit_char_whitelist", "0123456789۰۱۲۳۴۵۶۷۸۹.,٬،")
                    numericApi.setImage(bitmap)
                    val numericText = numericApi.getUTF8Text().orEmpty()
                    val mainDigits = bestText.count { it.isDigit() }
                    val numericDigits = numericText.count { it.isDigit() }
                    if (numericDigits >= 6 && numericDigits > mainDigits) {
                        bestText = bestText.trim() + "\n" + numericText.trim()
                        bestConfidence = max(bestConfidence, numericApi.meanConfidence())
                    }
                }
            } finally {
                numericApi.recycle()
            }
        }

        bitmap.recycle()
        return OcrResult(bestText, bestConfidence.coerceAtLeast(0))
    }

    private fun ensureModels() {
        tessdata.mkdirs()
        for (name in listOf("fas.traineddata", "eng.traineddata")) {
            val out = File(tessdata, name)
            if (!out.exists() || out.length() < 100_000) {
                context.assets.open("tessdata/" + name).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun loadForOcr(file: File): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 2600 || opts.outHeight / sample > 2600) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("تصویر قابل خواندن نیست.")

        val rotation = runCatching {
            when (ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val rotated = if (rotation == 0f) decoded else {
            val m = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                decoded.recycle()
            }
        }

        val gray = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        canvas.drawColor(Color.WHITE)

        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 1.45f
            val translate = (-.5f * contrast + .5f) * 255f + 12f
            postConcat(ColorMatrix(floatArrayOf(
                contrast,0f,0f,0f,translate,
                0f,contrast,0f,0f,translate,
                0f,0f,contrast,0f,translate,
                0f,0f,0f,1f,0f
            )))
        }
        canvas.drawBitmap(rotated, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        })
        rotated.recycle()
        return gray
    }
}
