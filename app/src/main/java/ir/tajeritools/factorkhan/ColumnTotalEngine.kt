package ir.tajeritools.factorkhan

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class ColumnInvoiceRow(
    val row: Int,
    val quantity: Double?,
    val unitPrice: Long?,
    val printedTotal: Long?,
    val calculatedTotal: Long?,
    val confidence: Int,
    val warning: String = ""
)

data class ColumnTotalResult(
    val rows: List<ColumnInvoiceRow>,
    val calculatedGrandTotal: Long,
    val printedRowsTotal: Long,
    val acceptedRows: Int,
    val rejectedRows: Int,
    val layoutNote: String
)

/**
 * فاکتورهای فرم‌دار را به‌جای OCR کل صفحه، بر اساس جدول می‌خواند.
 * هدف عمداً فقط سه ستون عددی است: تعداد، قیمت واحد، مبلغ ردیف.
 * این کار مانع قاطی‌شدن شرح کالا و دست‌خط فارسی با اعداد می‌شود.
 */
class ColumnTotalEngine(private val context: Context) {
    private val root = File(context.filesDir, "tesseract")
    private val tessdata = File(root, "tessdata")

    fun analyze(imageFile: File): ColumnTotalResult {
        ensureModels()
        val source = loadUpright(imageFile)
        val working = resize(source, 1800)
        if (source !== working) source.recycle()

        try {
            val verticals = detectVerticalGridLines(working)
            val horizontals = detectHorizontalGridLines(working)
            val columns = chooseInvoiceColumns(working.width, verticals)
            val rows = chooseItemRows(working.height, horizontals)

            val output = mutableListOf<ColumnInvoiceRow>()
            rows.forEachIndexed { index, yRange ->
                val qtyCrop = safeCrop(working, columns.qty.first, yRange.first, columns.qty.last - columns.qty.first, yRange.last - yRange.first)
                val unitCrop = safeCrop(working, columns.unit.first, yRange.first, columns.unit.last - columns.unit.first, yRange.last - yRange.first)
                val totalCrop = safeCrop(working, columns.total.first, yRange.first, columns.total.last - columns.total.first, yRange.last - yRange.first)

                val qtyOcr = recognizeNumber(qtyCrop, isQuantity = true)
                val unitOcr = recognizeNumber(unitCrop, isQuantity = false)
                val totalOcr = recognizeNumber(totalCrop, isQuantity = false)
                qtyCrop.recycle(); unitCrop.recycle(); totalCrop.recycle()

                val q = parseQty(qtyOcr.text)
                val u = parseMoneyStrict(unitOcr.text)
                val p = parseMoneyStrict(totalOcr.text)
                val calc = if (q != null && u != null && q > 0.0 && u > 0L) {
                    (q * u.toDouble()).roundToLong()
                } else null

                val confidence = listOf(qtyOcr.confidence, unitOcr.confidence, totalOcr.confidence)
                    .filter { it > 0 }
                    .let { if (it.isEmpty()) 0 else it.average().toInt() }

                // سطرهای خالی جدول را کنار بگذار.
                if (q == null && u == null && p == null) return@forEachIndexed

                val warning = when {
                    calc != null && p != null && !closeEnough(calc, p) ->
                        "مبلغ چاپی با تعداد × قیمت واحد نمی‌خواند"
                    q == null || u == null ->
                        "تعداد یا قیمت واحد با اطمینان کافی خوانده نشد"
                    else -> ""
                }

                output += ColumnInvoiceRow(
                    row = index + 1,
                    quantity = q,
                    unitPrice = u,
                    printedTotal = p,
                    calculatedTotal = calc,
                    confidence = confidence,
                    warning = warning
                )
            }

            val accepted = output.filter { it.calculatedTotal != null }
            val grand = accepted.sumOf { it.calculatedTotal ?: 0L }
            val printed = output.mapNotNull { it.printedTotal }.sum()

            return ColumnTotalResult(
                rows = output,
                calculatedGrandTotal = grand,
                printedRowsTotal = printed,
                acceptedRows = accepted.size,
                rejectedRows = output.size - accepted.size,
                layoutNote = columns.note + " | " +
                    if (horizontals.size >= 5) "ردیف‌ها از خطوط جدول تشخیص داده شدند."
                    else "ردیف‌ها با تقسیم‌بندی تقریبی جدول تشخیص داده شدند."
            )
        } finally {
            working.recycle()
        }
    }

    private data class NumOcr(val text: String, val confidence: Int)

    private data class InvoiceColumns(
        val total: IntRange,
        val unit: IntRange,
        val qty: IntRange,
        val note: String
    )

    private fun chooseInvoiceColumns(width: Int, lines: List<Int>): InvoiceColumns {
        // در فرم‌های رایج فارسی از چپ به راست: مبلغ ردیف | قیمت واحد | تعداد | شرح.
        // اگر شبکه واضح باشد، از خطوط واقعی جدول استفاده می‌کنیم.
        val merged = lines.sorted().filter { it > width * 0.02 && it < width * 0.98 }
        val gaps = merged.zipWithNext().map { it.first to it.second }
            .filter { (a,b) -> b - a > width * 0.055 }

        if (gaps.size >= 4) {
            // شرح معمولاً پهن‌ترین ناحیه سمت راست است. سه خانه عددی درست قبل از آن را می‌گیریم.
            val candidates = gaps.filter { it.first < width * 0.82 }
            if (candidates.size >= 3) {
                val numeric = candidates.takeLast(3)
                val total = numeric[0]
                val unit = numeric[1]
                val qty = numeric[2]
                return InvoiceColumns(
                    insetRange(total.first, total.second),
                    insetRange(unit.first, unit.second),
                    insetRange(qty.first, qty.second),
                    "ستون‌ها از خطوط واقعی جدول پیدا شدند."
                )
            }
        }

        // fallback مخصوص فرم نمونه: PRICE / UNITY PRICE / QTY / DESCRIPTION
        return InvoiceColumns(
            ((width * 0.055).toInt())..((width * 0.445).toInt()),
            ((width * 0.445).toInt())..((width * 0.625).toInt()),
            ((width * 0.625).toInt())..((width * 0.755).toInt()),
            "خطوط جدول کامل پیدا نشد؛ از قالب استاندارد فاکتور استفاده شد."
        )
    }

    private fun insetRange(a: Int, b: Int): IntRange {
        val pad = max(3, ((b - a) * 0.06).toInt())
        return (a + pad)..(b - pad)
    }

    private fun chooseItemRows(height: Int, horizontal: List<Int>): List<IntRange> {
        val useful = horizontal.sorted()
            .filter { it > height * 0.08 && it < height * 0.78 }

        val gaps = useful.zipWithNext()
            .filter { (a,b) ->
                val h = b - a
                h > height * 0.025 && h < height * 0.13
            }

        if (gaps.size >= 3) {
            return gaps.take(30).map { (a,b) ->
                val pad = max(2, ((b - a) * 0.08).toInt())
                (a + pad)..(b - pad)
            }
        }

        // fallback: ناحیه آیتم‌ها معمولاً 15 تا 58 درصد ارتفاع صفحه است.
        val top = (height * 0.14).toInt()
        val bottom = (height * 0.62).toInt()
        val rowH = max(32, ((bottom - top) / 10.0).toInt())
        val out = mutableListOf<IntRange>()
        var y = top
        while (y + rowH <= bottom && out.size < 20) {
            out += (y + 2)..(y + rowH - 2)
            y += rowH
        }
        return out
    }

    private fun detectVerticalGridLines(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val y0 = (h * 0.08).toInt()
        val y1 = (h * 0.72).toInt()
        val scores = IntArray(w)

        for (x in 0 until w) {
            var score = 0
            var y = y0
            while (y < y1) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val redGrid = r > g * 1.12 && r > b * 1.12 && r > 70
                val dark = (r + g + b) / 3 < 95
                if (redGrid || dark) score++
                y += 3
            }
            scores[x] = score
        }
        val threshold = ((y1 - y0) / 3 * 0.30).toInt()
        return peaks(scores, threshold, max(4, w / 180))
    }

    private fun detectHorizontalGridLines(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val x0 = (w * 0.03).toInt()
        val x1 = (w * 0.95).toInt()
        val scores = IntArray(h)

        for (y in 0 until h) {
            var score = 0
            var x = x0
            while (x < x1) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val redGrid = r > g * 1.12 && r > b * 1.12 && r > 70
                val dark = (r + g + b) / 3 < 85
                if (redGrid || dark) score++
                x += 4
            }
            scores[y] = score
        }
        val threshold = ((x1 - x0) / 4 * 0.23).toInt()
        return peaks(scores, threshold, max(3, h / 300))
    }

    private fun peaks(scores: IntArray, threshold: Int, mergeDistance: Int): List<Int> {
        val groups = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        scores.indices.forEach { i ->
            if (scores[i] >= threshold) {
                if (current.isNotEmpty() && i - current.last() > mergeDistance) {
                    groups += current
                    current = mutableListOf()
                }
                current += i
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups.map { g -> g.maxByOrNull { scores[it] } ?: g[g.size / 2] }
    }

    private fun recognizeNumber(cell: Bitmap, isQuantity: Boolean): NumOcr {
        val variants = listOf(
            preprocess(cell, threshold = 165, invert = false),
            preprocess(cell, threshold = 195, invert = false),
            preprocess(cell, threshold = 180, invert = true)
        )

        var best = NumOcr("", 0)
        variants.forEach { bmp ->
            val api = TessBaseAPI()
            try {
                if (api.init(root.absolutePath, "fas+eng")) {
                    api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
                    api.setVariable("tessedit_char_whitelist", if (isQuantity)
                        "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩.," else
                        "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩.,٬،")
                    api.setVariable("classify_bln_numeric_mode", "1")
                    api.setImage(bmp)
                    val text = api.getUTF8Text().orEmpty().trim()
                    val conf = api.meanConfidence().coerceIn(0, 100)
                    val digitCount = InvoiceAnalyzer.normalizeDigits(text).count { it.isDigit() }
                    val bestDigits = InvoiceAnalyzer.normalizeDigits(best.text).count { it.isDigit() }
                    if (digitCount > bestDigits || (digitCount == bestDigits && conf > best.confidence)) {
                        best = NumOcr(text, conf)
                    }
                }
            } finally {
                api.recycle()
                bmp.recycle()
            }
        }
        return best
    }

    private fun preprocess(source: Bitmap, threshold: Int, invert: Boolean): Bitmap {
        val scale = if (source.height < 120) 3 else 2
        val scaled = Bitmap.createScaledBitmap(source, source.width * scale, source.height * scale, true)
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        for (i in px.indices) {
            val c = px[i]
            val gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            val ink = gray < threshold
            val black = if (invert) !ink else ink
            px[i] = if (black) Color.BLACK else Color.WHITE
        }
        out.setPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        scaled.recycle()
        return out
    }

    private fun parseQty(text: String): Double? {
        val normalized = InvoiceAnalyzer.normalizeDigits(text)
            .replace(Regex("[^0-9.]"), "")
        val v = normalized.toDoubleOrNull() ?: return null
        return v.takeIf { it > 0.0 && it <= 9999.0 }
    }

    private fun parseMoneyStrict(text: String): Long? {
        val normalized = InvoiceAnalyzer.normalizeDigits(text)
            .replace(Regex("[^0-9]"), "")
        if (normalized.length < 2) return null
        val n = normalized.toLongOrNull() ?: return null
        return n.takeIf { it > 0L }
    }

    private fun closeEnough(a: Long, b: Long): Boolean {
        val tol = max(1500L, (max(a,b) * 0.03).toLong())
        return abs(a - b) <= tol
    }

    private fun safeCrop(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val xx = x.coerceIn(0, bitmap.width - 1)
        val yy = y.coerceIn(0, bitmap.height - 1)
        val ww = width.coerceAtLeast(1).coerceAtMost(bitmap.width - xx)
        val hh = height.coerceAtLeast(1).coerceAtMost(bitmap.height - yy)
        return Bitmap.createBitmap(bitmap, xx, yy, ww, hh)
    }

    private fun resize(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val ratio = maxWidth.toFloat() / source.width
        return Bitmap.createScaledBitmap(source, maxWidth, (source.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun loadUpright(file: File): Bitmap {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: error("تصویر قابل خواندن نیست.")
        val rotation = runCatching {
            when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return original
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
            .also { original.recycle() }
    }

    private fun ensureModels() {
        tessdata.mkdirs()
        for (name in listOf("fas.traineddata", "eng.traineddata")) {
            val out = File(tessdata, name)
            if (!out.exists() || out.length() < 100_000) {
                context.assets.open("tessdata/$name").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
