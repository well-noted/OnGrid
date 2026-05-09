package com.ongrid.app.data.model

import com.google.gson.annotations.SerializedName

data class OllamaModel(
    val name: String,
    val model: String = name,
    @SerializedName("modified_at") val modifiedAt: String = "",
    val size: Long = 0L,
    val digest: String = "",
    val details: ModelDetails? = null
) {
    val displayName: String get() = name.substringBefore(":")
    val tag: String get() = name.substringAfter(":", "latest")
    val sizeFormatted: String get() = when {
        size >= 1_000_000_000L -> "${"%.1f".format(size / 1_000_000_000.0)} GB"
        size >= 1_000_000L -> "${"%.1f".format(size / 1_000_000.0)} MB"
        size >= 1_000L -> "${"%.1f".format(size / 1_000.0)} KB"
        else -> "$size B"
    }
}

data class ModelDetails(
    val format: String = "",
    @SerializedName("family") val family: String = "",
    @SerializedName("parameter_size") val parameterSize: String = "",
    @SerializedName("quantization_level") val quantizationLevel: String = ""
)

data class OllamaTagsResponse(
    val models: List<OllamaModel> = emptyList()
)

data class OllamaVersionResponse(
    val version: String = ""
)
