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

fun parameterShapes(optional: Int = 1, vararg values: String, required: String): String = required
class ParameterShapes(val optional: Int = 1, val required: String)
class ParameterOuter { inner class Child(val count: Int = 1) }

@JvmOverloads
fun overloadNavigation(optional: Int = 1, required: String, suffix: String = "!"): String = "$optional-$required$suffix"
class OverloadConstructor @JvmOverloads constructor(val count: Int = 1, val label: String = "ok")

fun argumentNavigation(optional: Int = 1, `odd name`: String = "ok", block: () -> String): String = "$optional-${`odd name`}-${block()}"
fun argumentNavigationCaller(): String = argumentNavigation(optional = 2, "value") { "lambda" }
fun argumentNavigationEscapedCaller(): String = argumentNavigation(`odd name` = "value") { "lambda" }
fun argumentNavigationBareCaller(): String = argumentNavigation { "lambda" }

fun typedNavigation(value: Int): Int = value
fun typedNavigation(value: String): String = value
fun <T> genericNavigation(value: T): T = value
class TypedReceiver { fun typedNavigation(value: Long): Long = value }

inline fun debugInline(block: () -> String): String { greeting("inline"); return block() }
fun debugInlineCaller(): String = debugInline { greeting("callsite") }
suspend fun debugSuspendPoint(): String = kotlin.coroutines.suspendCoroutine { continuation -> continuation.resumeWith(Result.success("ok")) }
suspend fun debugSuspendCaller(): String { val value = debugSuspendPoint(); return greeting(value) }

// Metadata must preserve these source types despite erased JVM signatures.
typealias NullableLabels = List<String?>
fun <T : CharSequence> richTypes(value: T, labels: NullableLabels, vararg rest: T): Map<String, T?> = mapOf("value" to value)
fun String?.nullableExtension(values: List<*>): String? = this
class GenericBox<T : CharSequence>(val value: T?)
