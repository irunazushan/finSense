package com.finsense.core.dto.kafka

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RawTransactionEvent(
    val transactionId: UUID,
    val userId: UUID,
    val amount: BigDecimal,
    val description: String? = null,
    val merchantName: String? = null,
    val mccCode: String? = null,
    val timestamp: Instant
)
