package com.finsense.coach.analytics

import com.finsense.coach.config.AppProperties
import com.finsense.coach.repository.TransactionAnalyticsRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class TransactionAnalyzer(
    private val transactionAnalyticsRepository: TransactionAnalyticsRepository,
    private val appProperties: AppProperties
) {
    @Transactional(readOnly = true)
    fun analyze(userId: UUID, periodDays: Int, now: Instant = Instant.now()): AnalyticsSnapshot {
        val from = now.minusSeconds(periodDays.toLong() * 24 * 3600)
        val previousFrom = from.minusSeconds(periodDays.toLong() * 24 * 3600)

        return AnalyticsSnapshot(
            spendingByCategory = transactionAnalyticsRepository.getSpendingByCategory(userId, from, now),
            monthlyDelta = transactionAnalyticsRepository.getMonthlyDelta(
                userId = userId,
                currentFrom = from,
                currentTo = now,
                previousFrom = previousFrom,
                previousTo = from
            ),
            topMerchants = transactionAnalyticsRepository.getTopMerchants(
                userId = userId,
                from = from,
                to = now,
                limit = appProperties.analytics.topMerchantsLimit
            ),
            spikes = transactionAnalyticsRepository.detectSpikes(userId, from, now)
        )
    }
}
