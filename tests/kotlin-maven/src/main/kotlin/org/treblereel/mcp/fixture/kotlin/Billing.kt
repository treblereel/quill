package org.treblereel.mcp.fixture.kotlin

data class Invoice(val id: String, var state: String = "new")

class Payment(val amount: Int)

@JvmInline
value class InvoiceId(val value: String)

class PropertyShapes {
    val isActive: Boolean = true

    @JvmField
    val exposed: String = "visible"

    val delegated: String by lazy { "ready" }

    companion object {
        const val PREFIX: String = "invoice"

        @JvmStatic
        fun lookup(id: InvoiceId): Invoice? =
            BillingRegistry.invoices.firstOrNull { it.id == id.value }
    }
}

object BillingRegistry {
    val invoices: MutableList<Invoice> = mutableListOf()
}

fun charge(payment: Payment, retries: Int = 1): Invoice =
    Invoice("${payment.amount}-$retries")

fun loadInvoice(id: InvoiceId): Invoice? =
    BillingRegistry.invoices.firstOrNull { it.id == id.value }

fun greeting(name: String = "world"): String = "Hello, $name"

suspend fun fetchInvoice(id: String): Invoice? =
    BillingRegistry.invoices.firstOrNull { it.id == id }
