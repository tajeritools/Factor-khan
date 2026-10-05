package ir.tajeritools.factorkhan

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class InvoiceActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var customerId = -1L
    private var invoiceId: Long? = null
    private var imageFile: File? = null

    private lateinit var titleInput: EditText
    private lateinit var imageView: ImageView
    private lateinit var ocrInput: EditText
    private lateinit var manualTotalInput: EditText
    private lateinit var resultText: TextView
    private lateinit var scanButton: Button
    private lateinit var itemContainer: LinearLayout
    private lateinit var itemHint: TextView

    private val rowViews = mutableListOf<ItemRowViews>()
    private val worker = Executors.newSingleThreadExecutor()

    private data class ItemRowViews(
        val root: LinearLayout,
        val description: EditText,
        val quantity: EditText,
        val unitPrice: EditText,
        val printedTotal: EditText,
        val status: TextView,
        val confidence: Int
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { copyUriToPrivateFile(uri) }
            .onSuccess {
                setImage(it)
                runOcr()
            }
            .onFailure { toast("خطا در باز کردن تصویر: " + (it.message ?: "")) }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            imageFile?.let { setImage(it) }
            runOcr()
        } else {
            toast("عکسی ثبت نشد.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        customerId = intent.getLongExtra("customer_id", -1L)
        invoiceId = intent.getLongExtra("invoice_id", -1L).takeIf { it > 0L }

        if (customerId <= 0L) {
            finish()
            return
        }

        setContentView(buildUi())
        invoiceId?.let { loadInvoice(it) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = verticalRoot()

        root.addView(actionButton("← بازگشت").apply {
            setOnClickListener { finish() }
        })
        root.addView(titleText("فاکتور حرفه‌ای", 27f))

        root.addView(TextView(this).apply {
            text = "عکس فاکتور را بده؛ برنامه متن، تعداد، قیمت واحد و مبلغ ردیف‌ها را استخراج می‌کند. قبل از ذخیره می‌توانی همه اعداد را اصلاح کنی."
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(10))
        })

        titleInput = EditText(this).apply {
            hint = "عنوان فاکتور"
            setText(
                "فاکتور " +
                    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
            )
        }
        root.addView(titleInput)

        val imageActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        imageActions.addView(
            actionButton("📷 عکس گرفتن").apply {
                setOnClickListener { startCamera() }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        imageActions.addView(
            actionButton("🖼 آپلود تصویر").apply {
                setOnClickListener { pickImage.launch("image/*") }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(imageActions)

        imageView = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = dp(190)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(imageView)

        scanButton = actionButton("🔎 خواندن عکس و استخراج اقلام").apply {
            setOnClickListener { runOcr() }
        }
        root.addView(scanButton)

        root.addView(titleText("متن OCR", 18f))
        ocrInput = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            minLines = 7
            hint = "متن تشخیص‌داده‌شده اینجا می‌آید. می‌توانی اصلاحش کنی."
        }
        root.addView(ocrInput)

        root.addView(actionButton("↻ استخراج دوباره اقلام از متن").apply {
            setOnClickListener {
                extractItemsFromText(showToast = true)
                analyze()
            }
        })

        root.addView(titleText("اقلام فاکتور", 20f))
        itemHint = TextView(this).apply {
            text = "بعد از خواندن عکس، ردیف‌های تشخیص‌داده‌شده اینجا می‌آیند."
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(itemHint)

        itemContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(itemContainer)

        root.addView(actionButton("＋ افزودن ردیف دستی").apply {
            setOnClickListener {
                addItemRow(
                    InvoiceItem(
                        description = "",
                        quantity = 1.0,
                        unitPrice = 0L,
                        printedRowTotal = null,
                        confidence = 100
                    )
                )
                analyze()
            }
        })

        manualTotalInput = EditText(this).apply {
            hint = "جمع کل چاپ‌شده روی فاکتور (اختیاری)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(manualTotalInput)

        root.addView(actionButton("🧮 محاسبه و کنترل فاکتور").apply {
            setOnClickListener { analyze() }
        })

        resultText = TextView(this).apply {
            gravity = Gravity.RIGHT
            textSize = 17f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(resultText)

        root.addView(actionButton("💾 ذخیره فاکتور").apply {
            setOnClickListener { save() }
        })

        if (invoiceId != null) {
            root.addView(actionButton("🗑 حذف این فاکتور").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@InvoiceActivity)
                        .setTitle("حذف فاکتور؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            invoiceId?.let { db.deleteInvoice(it) }
                            finish()
                        }
                        .show()
                }
            })
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun startCamera() {
        val dir = File(filesDir, "invoice_images").apply { mkdirs() }
        val file = File(dir, "invoice_" + System.currentTimeMillis() + ".jpg")
        imageFile = file
        val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
        takePhoto.launch(uri)
    }

    private fun copyUriToPrivateFile(uri: Uri): File {
        val dir = File(filesDir, "invoice_images").apply { mkdirs() }
        val out = File(dir, "invoice_" + System.currentTimeMillis() + ".img")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "فایل قابل خواندن نیست" }
            FileOutputStream(out).use { output ->
                input.copyTo(output)
            }
        }
        return out
    }

    private fun setImage(file: File) {
        imageFile = file
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
        imageView.setImageBitmap(bmp)
    }

    private fun runOcr() {
        val file = imageFile
        if (file == null || !file.exists()) {
            toast("اول از فاکتور عکس بگیرید یا تصویر را آپلود کنید.")
            return
        }

        scanButton.isEnabled = false
        scanButton.text = "در حال خواندن عکس..."

        worker.execute {
            runCatching { OcrEngine(this).recognize(file) }
                .onSuccess { result ->
                    runOnUiThread {
                        ocrInput.setText(result.text)
                        scanButton.isEnabled = true
                        scanButton.text = "🔎 خواندن عکس و استخراج اقلام"

                        extractItemsFromText(showToast = false)

                        resultText.text =
                            "دقت تقریبی OCR: " + result.confidence +
                                "%\nاقلام استخراج شدند؛ تعداد و قیمت‌ها را کنترل کن."
                        analyze()
                    }
                }
                .onFailure { e ->
                    runOnUiThread {
                        scanButton.isEnabled = true
                        scanButton.text = "🔎 خواندن عکس و استخراج اقلام"
                        toast("OCR انجام نشد: " + (e.message ?: "خطای ناشناخته"))
                    }
                }
        }
    }

    private fun extractItemsFromText(showToast: Boolean) {
        val items = InvoiceAnalyzer.extractItems(ocrInput.text.toString())
        rowViews.clear()
        itemContainer.removeAllViews()

        if (items.isEmpty()) {
            itemHint.text = "ردیف مطمئنی پیدا نشد. می‌توانی ردیف‌ها را دستی اضافه کنی یا متن OCR را اصلاح و دوباره استخراج کنی."
            if (showToast) toast("ردیف مطمئنی پیدا نشد.")
            return
        }

        itemHint.text = items.size.toString() + " ردیف پیدا شد. اعداد را بررسی و در صورت نیاز اصلاح کن."
        items.forEach { addItemRow(it) }
        if (showToast) toast(items.size.toString() + " ردیف استخراج شد.")
    }

    private fun addItemRow(item: InvoiceItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF7F7F7.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }

        val description = EditText(this).apply {
            hint = "شرح کالا"
            setText(item.description)
            gravity = Gravity.RIGHT
        }
        card.addView(description)

        val numbers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val quantity = EditText(this).apply {
            hint = "تعداد"
            setText(qtyText(item.quantity))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
        }
        val unitPrice = EditText(this).apply {
            hint = "قیمت واحد"
            setText(if (item.unitPrice > 0) item.unitPrice.toString() else "")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
        }
        val printedTotal = EditText(this).apply {
            hint = "مبلغ ردیف"
            setText(item.printedRowTotal?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
        }

        numbers.addView(quantity, LinearLayout.LayoutParams(0, -2, 0.7f))
        numbers.addView(unitPrice, LinearLayout.LayoutParams(0, -2, 1.2f))
        numbers.addView(printedTotal, LinearLayout.LayoutParams(0, -2, 1.2f))
        card.addView(numbers)

        val status = TextView(this).apply {
            gravity = Gravity.RIGHT
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(status)

        val remove = actionButton("حذف ردیف")
        card.addView(remove)

        val row = ItemRowViews(
            root = card,
            description = description,
            quantity = quantity,
            unitPrice = unitPrice,
            printedTotal = printedTotal,
            status = status,
            confidence = item.confidence
        )
        rowViews += row

        remove.setOnClickListener {
            rowViews.remove(row)
            itemContainer.removeView(card)
            analyze()
        }

        itemContainer.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(8))
            }
        )

        updateRowStatus(row)
    }

    private fun currentItems(): List<InvoiceItem> {
        return rowViews.mapNotNull { row ->
            val q = InvoiceAnalyzer.parseNumber(row.quantity.text.toString()) ?: return@mapNotNull null
            val unit = InvoiceAnalyzer.parseMoney(row.unitPrice.text.toString()) ?: return@mapNotNull null
            if (q <= 0.0 || unit < 0L) return@mapNotNull null

            InvoiceItem(
                description = row.description.text.toString().trim().ifBlank { "کالا" },
                quantity = q,
                unitPrice = unit,
                printedRowTotal = InvoiceAnalyzer.parseMoney(row.printedTotal.text.toString()),
                confidence = row.confidence
            )
        }
    }

    private fun updateRowStatus(row: ItemRowViews) {
        val q = InvoiceAnalyzer.parseNumber(row.quantity.text.toString())
        val unit = InvoiceAnalyzer.parseMoney(row.unitPrice.text.toString())
        val printed = InvoiceAnalyzer.parseMoney(row.printedTotal.text.toString())

        if (q == null || unit == null) {
            row.status.text = "تعداد یا قیمت واحد را وارد کن."
            return
        }

        val calc = (q * unit.toDouble()).toLong()
        val tolerance = printed?.let { max(1000L, (it * 0.005).toLong()) } ?: 0L

        row.status.text = when {
            printed == null ->
                "محاسبه: " + money(calc) + "  •  اطمینان OCR: " + row.confidence + "%"
            abs(calc - printed) <= tolerance ->
                "✓ تعداد × قیمت واحد = " + money(calc) + "  •  اطمینان OCR: " + row.confidence + "%"
            else ->
                "⚠ محاسبه " + money(calc) + " با مبلغ ردیف " + money(printed) + " نمی‌خواند."
        }
    }

    private fun analyze(): AnalysisResult {
        rowViews.forEach { updateRowStatus(it) }

        val items = currentItems()
        val manual = InvoiceAnalyzer.parseMoney(manualTotalInput.text.toString())
        val a = InvoiceAnalyzer.analyze(
            raw = ocrInput.text.toString(),
            manualReported = manual,
            structuredItems = items
        )

        val sb = StringBuilder()
        sb.append("تعداد ردیف‌های قابل محاسبه: ").append(items.size).append("\n")
        sb.append("جمع اقلام: ").append(money(a.subtotal)).append("\n")
        sb.append("مالیات/ارزش افزوده: ").append(money(a.tax)).append("\n")
        sb.append("تخفیف: ").append(money(a.discount)).append("\n")
        sb.append("جمع نهایی محاسبه‌شده: ").append(money(a.expectedFinal)).append("\n")
        sb.append("جمع کل چاپ‌شده: ").append(money(a.reportedTotal)).append("\n")
        a.difference?.let {
            sb.append("اختلاف: ").append(money(abs(it))).append("\n")
        }
        sb.append("\n").append(a.warning)

        resultText.text = sb.toString()
        return a
    }

    private fun save() {
        val items = currentItems()
        val analysis = analyze()

        if (ocrInput.text.toString().isBlank() && imageFile == null && items.isEmpty()) {
            toast("حداقل تصویر، متن یا یک ردیف فاکتور لازم است.")
            return
        }

        invoiceId = db.saveInvoice(
            id = invoiceId,
            customerId = customerId,
            title = titleInput.text.toString(),
            imagePath = imageFile?.absolutePath.orEmpty(),
            ocrText = ocrInput.text.toString(),
            computedTotal = analysis.expectedFinal,
            reportedTotal = analysis.reportedTotal,
            statusMessage = analysis.warning,
            items = items
        )

        toast("فاکتور و اقلام ذخیره شد.")
        finish()
    }

    private fun loadInvoice(id: Long) {
        val inv = db.invoice(id) ?: return
        customerId = inv.customerId
        titleInput.setText(inv.title)
        ocrInput.setText(inv.ocrText)
        inv.reportedTotal?.let { manualTotalInput.setText(it.toString()) }

        if (inv.imagePath.isNotBlank()) {
            val file = File(inv.imagePath)
            if (file.exists()) setImage(file)
        }

        val savedItems = db.invoiceItems(id)
        rowViews.clear()
        itemContainer.removeAllViews()
        if (savedItems.isNotEmpty()) {
            itemHint.text = savedItems.size.toString() + " ردیف ذخیره‌شده"
            savedItems.forEach { addItemRow(it) }
        } else if (inv.ocrText.isNotBlank()) {
            extractItemsFromText(showToast = false)
        }

        analyze()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
