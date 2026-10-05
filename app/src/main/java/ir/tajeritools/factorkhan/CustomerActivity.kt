package ir.tajeritools.factorkhan

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomerActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var customerId = -1L
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        customerId = intent.getLongExtra("customer_id", -1L)
        if (customerId <= 0 || db.customer(customerId) == null) {
            finish()
            return
        }
        root = verticalRoot()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        val c = db.customer(customerId) ?: run {
            finish()
            return
        }

        root.addView(actionButton("← بازگشت").apply { setOnClickListener { finish() } })
        root.addView(titleText(c.name, 27f))

        if (c.phone.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = "تلفن: " + c.phone
                gravity = Gravity.RIGHT
            })
        }
        if (c.notes.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = c.notes
                gravity = Gravity.RIGHT
            })
        }

        val summary = db.ledgerSummary(customerId)
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF2F7FF.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        summaryCard.addView(titleText("حساب مشتری", 21f))
        summaryCard.addView(TextView(this).apply {
            text =
                "بدهی تاییدشده: " + money(summary.verifiedDebt) +
                "\nپرداختی‌ها: " + money(summary.payments) +
                "\nمانده بدهی: " + money(summary.balance) +
                "\nفاکتور تاییدشده: " + summary.verifiedInvoiceCount +
                " | در انتظار بررسی: " + summary.pendingInvoiceCount
            gravity = Gravity.RIGHT
            textSize = 17f
        })
        root.addView(summaryCard)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            actionButton("＋ فاکتور جدید").apply {
                setOnClickListener {
                    startActivity(
                        Intent(this@CustomerActivity, InvoiceActivity::class.java)
                            .putExtra("customer_id", customerId)
                    )
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        actions.addView(
            actionButton("💳 ثبت پرداختی").apply { setOnClickListener { showPaymentDialog() } },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(actions)

        root.addView(actionButton("ویرایش مشتری").apply { setOnClickListener { editCustomer(c) } })

        root.addView(titleText("فاکتورها", 21f))
        val invoices = db.invoices(customerId)
        if (invoices.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "فاکتوری ثبت نشده است."
                gravity = Gravity.RIGHT
            })
        } else {
            val df = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
            invoices.forEach { inv ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFF7F7F7.toInt())
                        cornerRadius = dp(12).toFloat()
                    }
                }

                card.addView(titleText(inv.title, 18f))
                val verified = inv.isVerifiedForDebt()
                card.addView(TextView(this).apply {
                    text =
                        "تاریخ: " + df.format(Date(inv.createdAt)) +
                        "\nمبلغ: " + money(inv.computedTotal) +
                        "\nوضعیت حساب: " +
                        if (verified) "✓ به بدهی اضافه شده" else "⚠ هنوز به بدهی اضافه نشده" +
                        "\n" + inv.statusMessage
                    gravity = Gravity.RIGHT
                })

                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(
                    actionButton("باز کردن").apply {
                        setOnClickListener {
                            startActivity(
                                Intent(this@CustomerActivity, InvoiceActivity::class.java)
                                    .putExtra("customer_id", customerId)
                                    .putExtra("invoice_id", inv.id)
                            )
                        }
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                row.addView(
                    actionButton("حذف").apply {
                        setOnClickListener {
                            AlertDialog.Builder(this@CustomerActivity)
                                .setTitle("حذف فاکتور؟")
                                .setMessage(
                                    if (verified)
                                        "این فاکتور جزو بدهی است؛ با حذف آن، مبلغش هم از بدهی مشتری کم می‌شود."
                                    else
                                        "این فاکتور حذف می‌شود."
                                )
                                .setNegativeButton("انصراف", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    db.deleteInvoice(inv.id)
                                    render()
                                }
                                .show()
                        }
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                card.addView(row)

                root.addView(
                    card,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                )
            }
        }

        root.addView(titleText("پرداختی‌ها", 21f))
        val payments = db.payments(customerId)
        if (payments.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "هنوز پرداختی ثبت نشده است."
                gravity = Gravity.RIGHT
            })
        } else {
            val df = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
            payments.forEach { payment ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFF4FFF4.toInt())
                        cornerRadius = dp(12).toFloat()
                    }
                }

                card.addView(TextView(this).apply {
                    text =
                        "پرداخت: " + money(payment.amount) +
                        "\nتاریخ: " + df.format(Date(payment.createdAt)) +
                        if (payment.note.isNotBlank()) "\nیادداشت: " + payment.note else ""
                    gravity = Gravity.RIGHT
                    textSize = 16f
                })

                card.addView(actionButton("حذف پرداختی").apply {
                    setOnClickListener {
                        AlertDialog.Builder(this@CustomerActivity)
                            .setTitle("حذف پرداختی؟")
                            .setMessage("با حذف این پرداختی، مانده بدهی دوباره افزایش پیدا می‌کند.")
                            .setNegativeButton("انصراف", null)
                            .setPositiveButton("حذف") { _, _ ->
                                db.deletePayment(payment.id)
                                render()
                            }
                            .show()
                    }
                })

                root.addView(
                    card,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                )
            }
        }

        root.addView(
            actionButton("حذف کامل پروفایل مشتری").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@CustomerActivity)
                        .setTitle("حذف " + c.name + "؟")
                        .setMessage("پروفایل، فاکتورها و پرداختی‌های این مشتری حذف می‌شوند.")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            db.deleteCustomer(customerId)
                            finish()
                        }
                        .show()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(24), 0, 0)
            }
        )
    }

    private fun showPaymentDialog() {
        val box = verticalRoot()
        val amount = EditText(this).apply {
            hint = "مبلغ پرداختی"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val note = EditText(this).apply {
            hint = "توضیح یا شماره رسید (اختیاری)"
            gravity = Gravity.RIGHT
        }
        box.addView(amount)
        box.addView(note)

        val dialog = AlertDialog.Builder(this)
            .setTitle("ثبت پرداختی")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = InvoiceAnalyzer.parseMoney(amount.text.toString())
                if (value == null || value <= 0L) {
                    amount.error = "مبلغ معتبر وارد کن"
                } else {
                    db.addPayment(customerId, value, note.text.toString())
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun editCustomer(c: Customer) {
        val box = verticalRoot()
        val name = EditText(this).apply {
            setText(c.name)
            hint = "نام"
        }
        val phone = EditText(this).apply {
            setText(c.phone)
            hint = "تلفن"
        }
        val notes = EditText(this).apply {
            setText(c.notes)
            hint = "یادداشت"
        }
        box.addView(name)
        box.addView(phone)
        box.addView(notes)

        AlertDialog.Builder(this)
            .setTitle("ویرایش مشتری")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                if (name.text.toString().isNotBlank()) {
                    db.updateCustomer(
                        c.id,
                        name.text.toString(),
                        phone.text.toString(),
                        notes.text.toString()
                    )
                }
                render()
            }
            .show()
    }
}
