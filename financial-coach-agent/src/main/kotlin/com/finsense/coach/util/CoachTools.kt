package com.finsense.coach.util

import com.finsense.coach.model.CategoryDelta
import com.finsense.coach.model.CategorySpending
import com.finsense.coach.model.MerchantStat
import com.finsense.coach.model.SpikeInfo
import com.finsense.coach.config.AppProperties
import com.finsense.coach.repository.TransactionAnalyticsRepository
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CoachTools(
    private val analyticsRepository: TransactionAnalyticsRepository,
    private val appProperties: AppProperties
) {
    @Tool(description = "Returns spending totals grouped by category for a user and period in days")
    fun getSpendingByCategory(userId: String, periodDays: Int): List<CategorySpending> {
        val (from, to) = timeWindow(periodDays)
        return analyticsRepository.getSpendingByCategory(UUID.fromString(userId), from, to)
    }

    @Tool(description = "Returns category deltas between current and previous same-length periods")
    fun getMonthlyDelta(userId: String, periodDays: Int): List<CategoryDelta> {
        val (from, to) = timeWindow(periodDays)
        val previousFrom = from.minusSeconds(periodDays.toLong() * 24 * 3600)
        return analyticsRepository.getMonthlyDelta(
            userId = UUID.fromString(userId),
            currentFrom = from,
            currentTo = to,
            previousFrom = previousFrom,
            previousTo = from
        )
    }

    @Tool(description = "Returns top merchants by total spending for a user and period in days")
    fun getTopMerchants(userId: String, periodDays: Int, limit: Int?): List<MerchantStat> {
        val (from, to) = timeWindow(periodDays)
        val actualLimit = limit ?: appProperties.analytics.topMerchantsLimit
        return analyticsRepository.getTopMerchants(UUID.fromString(userId), from, to, actualLimit)
    }

    @Tool(description = "Detects unusual spending spikes by category for a user and period in days")
    fun detectSpikes(userId: String, periodDays: Int): List<SpikeInfo> {
        val (from, to) = timeWindow(periodDays)
        return analyticsRepository.detectSpikes(UUID.fromString(userId), from, to)
    }

    private fun timeWindow(periodDays: Int): Pair<Instant, Instant> {
        val safeDays = periodDays.coerceIn(1, 365)
        val to = Instant.now()
        val from = to.minusSeconds(safeDays.toLong() * 24 * 3600)
        return from to to
    }
}
