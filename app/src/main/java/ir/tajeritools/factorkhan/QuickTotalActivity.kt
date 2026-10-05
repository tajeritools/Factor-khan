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
