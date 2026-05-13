package com.ongrid.app

data class PendingSharedContent(
    val text: String,
    val subject: String? = null,
    val targetAgentId: String? = null
)
