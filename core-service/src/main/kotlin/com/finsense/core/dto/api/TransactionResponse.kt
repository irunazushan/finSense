package com.finsense.core.dto.api

import com.finsense.core.model.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransactionResponse(
    val transactionId: UUID,
    val userId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val description: String?,
    val merchantName: String?,
    val mccCode: String?,
    val transactionDate: Instant,
    val status: TransactionStatus,
    val category: String?,
    val classifierSource: String?,
    val classifierConfidence: Double?,
    val classifiedAt: Instant?
)
