package ir.tajeritools.factorkhan

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class QuickTotalActivity : AppCompatActivity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var currentFile: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val root = verticalRoot()
        root.addView(titleText("کنترل سریع جمع فاکتور", 27f))
        root.addView(TextView(this).apply {
            text = "فقط عکس فاکتور را انتخاب کن. برنامه ستون‌های تعداد، قیمت واحد و مبلغ ردیف را جدا می‌خواند، ضرب می‌کند و جمع کل محاسبه‌شده را می‌دهد."
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(12))
        })

        val upload = actionButton("🖼 انتخاب عکس فاکتور")
        upload.setOnClickListener { picker.launch(arrayOf("image/*")) }
        root.addView(upload)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(420)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))

        val run = actionButton("🧮 استخراج ستون‌ها و محاسبه جمع")
        run.setOnClickListener { analyze() }
        root.addView(run)

        root.addView(actionButton("🧠 Mistral OCR / Document AI").apply {
            setOnClickListener { analyzeWithMistral() }
        })

        root.addView(actionButton("🔑 تنظیم Mistral").apply {
            setOnClickListener { showMistralSettings() }
        })

        root.addView(actionButton("☁️ تحلیل آنلاین Azure Document Intelligence").apply {
            setOnClickListener { analyzeWithAzure() }
        })

        root.addView(actionButton("🔑 تنظیم Azure Document Intelligence").apply {
            setOnClickListener { showAzureSettings() }
        })

        status = TextView(this).apply {
            text = "هنوز عکسی انتخاب نشده."
            gravity = Gravity.RIGHT
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(status)

        result = TextView(this).apply {
            text = "نتیجه اینجا نمایش داده می‌شود."
            gravity = Gravity.RIGHT
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(result)

        return ScrollView(this).apply { addView(root) }
    }

    private fun loadImage(uri: Uri) {
        runCatching {
            val file = File(cacheDir, "quick_total_" + System.currentTimeMillis() + ".jpg")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            currentFile?.delete()
            currentFile = file
            preview.setImageURI(uri)
            status.text = "عکس آماده است."
            result.text = "دکمه «استخراج ستون‌ها و محاسبه جمع» را بزن."
        }.onFailure {
            Toast.makeText(this, it.message ?: "خطا در بازکردن تصویر", Toast.LENGTH_LONG).show()
        }
    }

    private fun analyze() {
        val file = currentFile
        if (file == null) {
            Toast.makeText(this, "اول عکس فاکتور را انتخاب کن.", Toast.LENGTH_SHORT).show()
            return
        }

        status.text = "در حال خواندن فقط ستون‌های عددی فاکتور..."
        result.text = ""

        Thread {
            runCatching {
                ColumnTotalEngine(this).analyze(file)
            }.onSuccess { r ->
                runOnUiThread {
                    status.text = "محاسبه انجام شد."
                    result.text = formatResult(r)
                }
            }.onFailure { e ->
                runOnUiThread {
                    status.text = "تحلیل انجام نشد."
                    result.text = e.message ?: "خطای ناشناخته"
                }
            }
        }.start()
    }

    private fun showMistralSettings() {
        val engine = MistralDocumentAi(this)
        val input = android.widget.EditText(this).apply {
            hint = "Mistral API Key"
            setText(engine.key())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تنظیم Mistral OCR")
            .setMessage("کلید Mistral را وارد کن. این حالت برای OCR اسناد و فاکتورهای فارسی و دست‌نویس استفاده می‌شود.")
            .setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                engine.saveKey(input.text.toString())
                Toast.makeText(this, "کلید Mistral ذخیره شد.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun analyzeWithMistral() {
        val file = currentFile
        if (file == null) {
            Toast.makeText(this, "اول عکس فاکتور را انتخاب کن.", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = MistralDocumentAi(this)
        if (!engine.configured()) {
            showMistralSettings()
            return
        }

        status.text = "در حال تحلیل فاکتور با Mistral OCR..."
        result.text = ""

        Thread {
            runCatching { engine.analyzeInvoice(file) }
                .onSuccess { a ->
                    val calculated = a.items.sumOf { it.computedRowTotal() }
                    val printedRows = a.items.mapNotNull { it.printedRowTotal }.sum()
                    runOnUiThread {
                        val sb = StringBuilder()
                        sb.append("Mistral OCR / Document AI").append("\n")
                        sb.append("جمع محاسبه‌شده از تعداد × قیمت واحد: ").append(money(calculated)).append("\n")
                        if (printedRows > 0) sb.append("جمع مبلغ‌های ردیف: ").append(money(printedRows)).append("\n")
                        sb.append("جمع کل تشخیص‌داده‌شده روی فاکتور: ").append(money(a.printedTotal)).append("\n")
                        if (a.printedTotal != null) {
                            sb.append("اختلاف محاسبه با جمع چاپ‌شده: ")
                                .append(money(kotlin.math.abs(calculated - a.printedTotal))).append("\n")
                        }
                        sb.append("تعداد ردیف‌های استخراج‌شده: ").append(a.items.size).append("\n")
                        sb.append(a.note).append("\n\n")
                        a.items.forEachIndexed { i, item ->
                            sb.append("ردیف ").append(i + 1)
                                .append(": تعداد=").append(qtyText(item.quantity))
                                .append(" | قیمت واحد=").append(money(item.unitPrice))
                                .append(" | حاصل=").append(money(item.computedRowTotal()))
                            if (item.printedRowTotal != null) {
                                sb.append(" | مبلغ نوشته‌شده=").append(money(item.printedRowTotal))
                            }
                            sb.append("\n")
                        }
                        status.text = "تحلیل Mistral انجام شد."
                        result.text = sb.toString()
                    }
                }
                .onFailure { e ->
                    runOnUiThread {
                        status.text = "تحلیل Mistral انجام نشد."
                        result.text = e.message ?: "خطای ناشناخته"
                    }
                }
        }.start()
    }

    private fun showAzureSettings() {
        val engine = AzureDocumentIntelligence(this)
        val box = verticalRoot()

        val endpoint = android.widget.EditText(this).apply {
            hint = "Endpoint مثال: https://xxxx.cognitiveservices.azure.com"
            setText(engine.endpoint())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = android.widget.EditText(this).apply {
            hint = "Azure Key"
            setText(engine.key())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        box.addView(endpoint)
        box.addView(key)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تنظیم Azure Document Intelligence")
            .setMessage("Endpoint و یکی از Keyهای سرویس Document Intelligence را وارد کن.")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                engine.save(endpoint.text.toString(), key.text.toString())
                Toast.makeText(this, "تنظیمات Azure ذخیره شد.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun analyzeWithAzure() {
        val file = currentFile
        if (file == null) {
            Toast.makeText(this, "اول عکس فاکتور را انتخاب کن.", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = AzureDocumentIntelligence(this)
        if (!engine.configured()) {
            showAzureSettings()
            return
        }

        status.text = "در حال تحلیل فاکتور با Azure Document Intelligence..."
        result.text = ""

        Thread {
            runCatching { engine.analyzeInvoice(file) }
                .onSuccess { a ->
                    val calculated = a.items.sumOf { it.computedRowTotal() }
                    val printedRows = a.items.mapNotNull { it.printedRowTotal }.sum()
                    runOnUiThread {
                        val sb = StringBuilder()
                        sb.append("Azure Document Intelligence").append("\n")
                        sb.append("جمع محاسبه‌شده از تعداد × قیمت واحد: ").append(money(calculated)).append("\n")
                        if (printedRows > 0) sb.append("جمع مبلغ‌های ردیف: ").append(money(printedRows)).append("\n")
                        sb.append("جمع کل تشخیص‌داده‌شده روی فاکتور: ").append(money(a.printedTotal)).append("\n")
                        if (a.printedTotal != null) {
                            sb.append("اختلاف محاسبه با جمع چاپ‌شده: ")
                                .append(money(kotlin.math.abs(calculated - a.printedTotal))).append("\n")
                        }
                        sb.append("تعداد ردیف‌های استخراج‌شده: ").append(a.items.size).append("\n")
                        sb.append(a.confidenceNote).append("\n\n")
                        a.items.forEachIndexed { i, item ->
                            sb.append("ردیف ").append(i + 1)
                                .append(": تعداد=").append(qtyText(item.quantity))
                                .append(" | قیمت واحد=").append(money(item.unitPrice))
                                .append(" | حاصل=").append(money(item.computedRowTotal()))
                            if (item.printedRowTotal != null) {
                                sb.append(" | مبلغ نوشته‌شده=").append(money(item.printedRowTotal))
                            }
                            sb.append("\n")
                        }
                        status.text = "تحلیل Azure انجام شد."
                        result.text = sb.toString()
                    }
                }
                .onFailure { e ->
                    runOnUiThread {
                        status.text = "تحلیل Azure انجام نشد."
                        result.text = e.message ?: "خطای ناشناخته"
                    }
                }
        }.start()
    }

    private fun formatResult(r: ColumnTotalResult): String {
        val sb = StringBuilder()
        sb.append("جمع کل محاسبه‌شده: ").append(money(r.calculatedGrandTotal)).append("\n")
        if (r.printedRowsTotal > 0) {
            sb.append("جمع مبلغ‌های خوانده‌شده از ستون PRICE: ").append(money(r.printedRowsTotal)).append("\n")
            val diff = abs(r.calculatedGrandTotal - r.printedRowsTotal)
            sb.append("اختلاف دو روش: ").append(money(diff)).append("\n")
        }
        sb.append("ردیف قابل محاسبه: ").append(r.acceptedRows).append("\n")
        sb.append("ردیف ناقص/نامطمئن: ").append(r.rejectedRows).append("\n\n")

        r.rows.forEach { row ->
            sb.append("ردیف ").append(row.row).append(": ")
            sb.append("تعداد=").append(row.quantity?.let { qtyText(it) } ?: "؟")
            sb.append(" | قیمت واحد=").append(money(row.unitPrice))
            sb.append(" | مبلغ محاسبه=").append(money(row.calculatedTotal))
            if (row.printedTotal != null) sb.append(" | مبلغ نوشته‌شده=").append(money(row.printedTotal))
            if (row.warning.isNotBlank()) sb.append("  ⚠ ").append(row.warning)
            sb.append("\n")
        }

        sb.append("\n").append(r.layoutNote)
        sb.append("\n\nاگر یک ردیف علامت ⚠ دارد، فقط همان ردیف را از روی عکس کنترل کن.")
        return sb.toString()
    }

    override fun onDestroy() {
        currentFile?.delete()
        super.onDestroy()
    }
}
