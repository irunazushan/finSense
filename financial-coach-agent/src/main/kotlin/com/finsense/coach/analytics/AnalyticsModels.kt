package com.finsense.coach.analytics

import java.math.BigDecimal
import java.time.LocalDate

data class CategorySpending(
    val category: String,
    val totalAmount: BigDecimal,
    val transactionCount: Int
)

data class CategoryDelta(
    val category: String,
    val currentAmount: BigDecimal,
    val previousAmount: BigDecimal,
    val deltaAmount: BigDecimal,
    val deltaPercent: Double?
)

data class MerchantStat(
    val merchantName: String,
    val totalAmount: BigDecimal,
    val transactionCount: Int
)

data class SpikeInfo(
    val category: String,
    val date: LocalDate,
    val baselineAmount: BigDecimal,
    val spikeAmount: BigDecimal
)

data class AnalyticsSnapshot(
    val spendingByCategory: List<CategorySpending>,
    val monthlyDelta: List<CategoryDelta>,
    val topMerchants: List<MerchantStat>,
    val spikes: List<SpikeInfo>
) {
    fun hasData(): Boolean {
        return spendingByCategory.isNotEmpty() || topMerchants.isNotEmpty()
    }
}
