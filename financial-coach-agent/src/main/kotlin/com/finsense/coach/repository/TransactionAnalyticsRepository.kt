package com.finsense.coach.repository

import com.finsense.coach.analytics.CategoryDelta
import com.finsense.coach.analytics.CategorySpending
import com.finsense.coach.analytics.MerchantStat
import com.finsense.coach.analytics.SpikeInfo
import java.time.Instant
import java.util.UUID

interface TransactionAnalyticsRepository {
    fun getSpendingByCategory(userId: UUID, from: Instant, to: Instant): List<CategorySpending>
    fun getMonthlyDelta(
        userId: UUID,
        currentFrom: Instant,
        currentTo: Instant,
        previousFrom: Instant,
        previousTo: Instant
    ): List<CategoryDelta>
    fun getTopMerchants(userId: UUID, from: Instant, to: Instant, limit: Int): List<MerchantStat>
    fun detectSpikes(userId: UUID, from: Instant, to: Instant): List<SpikeInfo>
}
