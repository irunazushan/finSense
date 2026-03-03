package com.finsense.core.dto.kafka

import java.time.Instant
import java.util.UUID

data class LlmClassifierResponseEvent(
    val requestId: UUID,
    val transactionId: UUID,
    val category: String,
    val confidence: Double,
    val processedAt: Instant
)
