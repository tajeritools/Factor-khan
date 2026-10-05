package ir.tajeritools.factorkhan

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
    private val worker = Executors.newSingleThreadExecutor()

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
        root.addView(titleText("فاکتور", 27f))

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
            minimumHeight = dp(180)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(imageView)

        scanButton = actionButton("تشخیص متن فاکتور").apply {
            setOnClickListener { runOcr() }
        }
        root.addView(scanButton)

        root.addView(titleText("متن تشخیص‌داده‌شده (قابل اصلاح)", 18f))
        ocrInput = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            minLines = 8
            hint = "بعد از OCR متن اینجا می‌آید. اگر عددی اشتباه خوانده شد، اصلاحش کنید."
        }
        root.addView(ocrInput)

        manualTotalInput = EditText(this).apply {
            hint = "جمع کل چاپ‌شده روی فاکتور (اختیاری)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(manualTotalInput)

        root.addView(actionButton("🧮 تحلیل و کنترل جمع").apply {
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
            root.addView(actionButton("حذف این فاکتور").apply {
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
        scanButton.text = "در حال خواندن فاکتور..."

        worker.execute {
            runCatching { OcrEngine(this).recognize(file) }
                .onSuccess { result ->
                    runOnUiThread {
                        ocrInput.setText(result.text)
                        scanButton.isEnabled = true
                        scanButton.text = "تشخیص متن فاکتور"
                        resultText.text =
                            "دقت تقریبی OCR: " + result.confidence +
                                "%\nحالا «تحلیل و کنترل جمع» را بزنید."
                        analyze()
                    }
                }
                .onFailure { e ->
                    runOnUiThread {
                        scanButton.isEnabled = true
                        scanButton.text = "تشخیص متن فاکتور"
                        toast("OCR انجام نشد: " + (e.message ?: "خطای ناشناخته"))
                    }
                }
        }
    }

    private fun analyze(): AnalysisResult {
        val manual = InvoiceAnalyzer.parseMoney(manualTotalInput.text.toString())
        val a = InvoiceAnalyzer.analyze(ocrInput.text.toString(), manual)

        val sb = StringBuilder()
        sb.append("جمع ردیف‌های تشخیص‌داده‌شده: ").append(money(a.subtotal)).append("\n")
        sb.append("مالیات/ارزش افزوده: ").append(money(a.tax)).append("\n")
        sb.append("تخفیف: ").append(money(a.discount)).append("\n")
        sb.append("جمع محاسبه‌شده: ").append(money(a.expectedFinal)).append("\n")
        sb.append("جمع کل فاکتور: ").append(money(a.reportedTotal)).append("\n")
        a.difference?.let {
            sb.append("اختلاف: ").append(money(abs(it))).append("\n")
        }
        sb.append("\n").append(a.warning)
        resultText.text = sb.toString()

        return a
    }

    private fun save() {
        val analysis = analyze()
        if (ocrInput.text.toString().isBlank() && imageFile == null) {
            toast("حداقل تصویر یا متن فاکتور لازم است.")
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
            statusMessage = analysis.warning
        )

        toast("فاکتور ذخیره شد.")
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
        analyze()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
