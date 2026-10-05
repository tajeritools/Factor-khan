package ir.tajeritools.factorkhan

import android.content.ContentValues
import java.util.Date

data class PaymentRecord(
    val id: Long,
    val customerId: Long,
    val amount: Long,
    val note: String,
    val createdAt: Long
)

data class CustomerLedgerSummary(
    val verifiedDebt: Long,
    val payments: Long,
    val balance: Long,
    val verifiedInvoiceCount: Int,
    val pendingInvoiceCount: Int
)

private fun DbHelper.ensurePaymentsTable() {
    writableDatabase.execSQL("""
        CREATE TABLE IF NOT EXISTS payments(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            customer_id INTEGER NOT NULL,
            amount INTEGER NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE CASCADE
        )
    """.trimIndent())
    writableDatabase.execSQL(
        "CREATE INDEX IF NOT EXISTS idx_payments_customer ON payments(customer_id, created_at DESC)"
    )
}

fun DbHelper.addPayment(customerId: Long, amount: Long, note: String = "", createdAt: Long = System.currentTimeMillis()): Long {
    require(amount > 0) { "مبلغ پرداختی باید بیشتر از صفر باشد." }
    ensurePaymentsTable()
    val values = ContentValues().apply {
        put("customer_id", customerId)
        put("amount", amount)
        put("note", note.trim())
        put("created_at", createdAt)
    }
    return writableDatabase.insertOrThrow("payments", null, values)
}

fun DbHelper.deletePayment(id: Long) {
    ensurePaymentsTable()
    writableDatabase.delete("payments", "id=?", arrayOf(id.toString()))
}

fun DbHelper.payments(customerId: Long): List<PaymentRecord> {
    ensurePaymentsTable()
    val out = mutableListOf<PaymentRecord>()
    readableDatabase.rawQuery(
        "SELECT id,customer_id,amount,note,created_at FROM payments WHERE customer_id=? ORDER BY created_at DESC",
        arrayOf(customerId.toString())
    ).use { c ->
        while (c.moveToNext()) {
            out += PaymentRecord(
                id = c.getLong(0),
                customerId = c.getLong(1),
                amount = c.getLong(2),
                note = c.getString(3),
                createdAt = c.getLong(4)
            )
        }
    }
    return out
}

fun InvoiceRecord.isVerifiedForDebt(): Boolean {
    return computedTotal != null &&
        computedTotal > 0L &&
        statusMessage.trim().startsWith("✓")
}

fun DbHelper.ledgerSummary(customerId: Long): CustomerLedgerSummary {
    ensurePaymentsTable()

    var verifiedDebt = 0L
    var verifiedCount = 0
    var pendingCount = 0

    invoices(customerId).forEach { inv ->
        if (inv.isVerifiedForDebt()) {
            verifiedDebt += inv.computedTotal ?: 0L
            verifiedCount++
        } else {
            pendingCount++
        }
    }

    var paid = 0L
    readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount),0) FROM payments WHERE customer_id=?",
        arrayOf(customerId.toString())
    ).use { c ->
        if (c.moveToFirst()) paid = c.getLong(0)
    }

    return CustomerLedgerSummary(
        verifiedDebt = verifiedDebt,
        payments = paid,
        balance = verifiedDebt - paid,
        verifiedInvoiceCount = verifiedCount,
        pendingInvoiceCount = pendingCount
    )
}
