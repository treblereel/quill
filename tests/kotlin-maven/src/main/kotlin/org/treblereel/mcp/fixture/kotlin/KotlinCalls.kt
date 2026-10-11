package org.treblereel.mcp.fixture.kotlin

fun defaultCaller(): String = greeting()
fun explicitCaller(): String = greeting("explicit")
fun constructorCaller(): Invoice = Invoice("id")
fun propertyCaller(invoice: Invoice): String {
    invoice.state = "paid"
    return invoice.state
}
fun facadeCaller(invoice: Invoice): Boolean = validateInvoice(invoice)
fun extensionCaller(invoice: Invoice): String = invoice.describe()
fun Invoice.describe(prefix: String = "invoice"): String = "$prefix-$id"

class StaticShapes {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun welcome(name: String = "world"): String = "Welcome, $name"
    }
}
