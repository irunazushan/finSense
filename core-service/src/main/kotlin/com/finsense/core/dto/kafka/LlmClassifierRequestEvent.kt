package com.finsense.core.dto.kafka

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class LlmClassifierRequestEvent(
    val requestId: UUID,
    val transactionId: UUID,
    val occurredAt: Instant,
    val transaction: TransactionContext,
    val confidence: Double,
    val predictedCategory: String,
    val history: List<HistoryTransaction>
)

data class TransactionContext(
    val userId: UUID,
    val amount: BigDecimal,
    val description: String?,
    val merchantName: String?,
    val mccCode: String?,
    val transactionDate: Instant
)

data class HistoryTransaction(
    val transactionId: UUID,
    val amount: BigDecimal,
    val category: String?,
    val merchantName: String?,
    val transactionDate: Instant
)
