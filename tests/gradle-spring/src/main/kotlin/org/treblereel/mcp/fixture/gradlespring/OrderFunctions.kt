package org.treblereel.mcp.fixture.gradlespring

fun formatOrder(id: String, prefix: String = "order"): String = "$prefix-$id"

suspend fun loadOrder(id: String): String = formatOrder(id)
