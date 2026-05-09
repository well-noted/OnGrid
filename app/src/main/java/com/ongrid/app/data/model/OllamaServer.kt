package com.ongrid.app.data.model

data class OllamaServer(
    val host: String,
    val port: Int = 11434,
    val version: String = "",
    val models: List<OllamaModel> = emptyList()
) {
    val baseUrl: String get() = "http://$host:$port"
    val displayName: String get() = "$host:$port"
}
