package com.finsense.core.dto.client

import java.math.BigDecimal

data class ClassifierRequest(
    val amount: BigDecimal,
    val description: String?,
    val merchantName: String?,
    val mccCode: String?
)

data class ClassifierResponse(
    val category: String,
    val confidence: Double
)
