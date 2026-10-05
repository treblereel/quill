@file:JvmName("BillingApi")
@file:JvmMultifileClass

package org.treblereel.mcp.fixture.kotlin

fun validateInvoice(invoice: Invoice): Boolean = invoice.id.isNotBlank()
