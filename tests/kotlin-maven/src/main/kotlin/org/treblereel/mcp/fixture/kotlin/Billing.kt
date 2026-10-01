package org.treblereel.mcp.fixture.kotlin

data class Invoice(val id: String, var state: String = "new")

class Payment(val amount: Int)

object BillingRegistry {
    val invoices: MutableList<Invoice> = mutableListOf()
}

fun charge(payment: Payment, retries: Int = 1): Invoice =
    Invoice("${payment.amount}-$retries")

suspend fun fetchInvoice(id: String): Invoice? =
    BillingRegistry.invoices.firstOrNull { it.id == id }
