package ir.tajeritools.factorkhan

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): ScrollView {
        val root = verticalRoot()
        root.addView(titleText("فاکتورخوان", 28f))
        root.addView(TextView(this).apply {
            text = "پروفایل مشتری‌ها و فاکتورها؛ اطلاعات فقط روی همین گوشی ذخیره می‌شود."
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(actionButton("🧮 کنترل سریع جمع فاکتور").apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QuickTotalActivity::class.java))
            }
        })

        root.addView(actionButton("＋ مشتری جدید").apply {
            setOnClickListener { showCustomerDialog() }
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        list.removeAllViews()
        val customers = db.customers()
        if (customers.isEmpty()) {
            list.addView(titleText("هنوز مشتری ثبت نشده است.", 16f))
            return
        }

        customers.forEach { c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF7F7F7.toInt())
                    cornerRadius = dp(14).toFloat()
                }
            }
            card.addView(titleText(c.name, 19f))
            if (c.phone.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = c.phone
                    gravity = Gravity.RIGHT
                })
            }

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(actionButton("باز کردن").apply {
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, CustomerActivity::class.java)
                            .putExtra("customer_id", c.id)
                    )
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actionButton("حذف").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حذف " + c.name + "؟")
                        .setMessage("تمام فاکتورهای این مشتری هم حذف می‌شوند.")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            db.deleteCustomer(c.id)
                            refresh()
                        }
                        .show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            list.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }
            )
        }
    }

    private fun showCustomerDialog() {
        val box = verticalRoot()
        val name = EditText(this).apply { hint = "نام مشتری" }
        val phone = EditText(this).apply {
            hint = "شماره تماس"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val notes = EditText(this).apply { hint = "یادداشت (اختیاری)" }
        box.addView(name)
        box.addView(phone)
        box.addView(notes)

        val dialog = AlertDialog.Builder(this)
            .setTitle("مشتری جدید")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().trim().isEmpty()) {
                    name.error = "نام را وارد کنید"
                } else {
                    db.addCustomer(
                        name.text.toString(),
                        phone.text.toString(),
                        notes.text.toString()
                    )
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }
}
