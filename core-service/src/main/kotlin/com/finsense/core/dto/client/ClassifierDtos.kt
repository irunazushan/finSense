package com.finsense.core.dto.client

import java.math.BigDecimal
import java.util.UUID

data class ClassifierRequest(
    val transactionId: UUID,
    val amount: BigDecimal,
    val description: String?,
    val merchantName: String?,
    val mccCode: String?
)

data class ClassifierResponse(
    val category: String,
    val confidence: Double,
    val transactionId: UUID? = null,
    val source: String? = null
)
