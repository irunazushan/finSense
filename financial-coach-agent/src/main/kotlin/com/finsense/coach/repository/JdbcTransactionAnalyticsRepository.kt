package com.finsense.coach.repository

import com.finsense.coach.analytics.CategoryDelta
import com.finsense.coach.analytics.CategorySpending
import com.finsense.coach.analytics.MerchantStat
import com.finsense.coach.analytics.SpikeInfo
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class JdbcTransactionAnalyticsRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : TransactionAnalyticsRepository {

    override fun getSpendingByCategory(userId: UUID, from: Instant, to: Instant): List<CategorySpending> {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to))

        val sql = """
            SELECT
                COALESCE(NULLIF(category, ''), 'UNCATEGORIZED') AS category,
                COALESCE(SUM(ABS(amount)), 0) AS total_amount,
                COUNT(*) AS tx_count
            FROM core.transactions
            WHERE user_id = :userId
              AND transaction_date >= :from
              AND transaction_date < :to
            GROUP BY COALESCE(NULLIF(category, ''), 'UNCATEGORIZED')
            ORDER BY total_amount DESC
        """.trimIndent()

        return jdbcTemplate.query(sql, params) { rs, _ ->
            CategorySpending(
                category = rs.getString("category"),
                totalAmount = rs.getBigDecimal("total_amount") ?: BigDecimal.ZERO,
                transactionCount = rs.getInt("tx_count")
            )
        }
    }

    override fun getMonthlyDelta(
        userId: UUID,
        currentFrom: Instant,
        currentTo: Instant,
        previousFrom: Instant,
        previousTo: Instant
    ): List<CategoryDelta> {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("currentFrom", Timestamp.from(currentFrom))
            .addValue("currentTo", Timestamp.from(currentTo))
            .addValue("previousFrom", Timestamp.from(previousFrom))
            .addValue("previousTo", Timestamp.from(previousTo))

        val sql = """
            WITH current_period AS (
                SELECT
                    COALESCE(NULLIF(category, ''), 'UNCATEGORIZED') AS category,
                    SUM(ABS(amount)) AS total_amount
                FROM core.transactions
                WHERE user_id = :userId
                  AND transaction_date >= :currentFrom
                  AND transaction_date < :currentTo
                GROUP BY COALESCE(NULLIF(category, ''), 'UNCATEGORIZED')
            ),
            previous_period AS (
                SELECT
                    COALESCE(NULLIF(category, ''), 'UNCATEGORIZED') AS category,
                    SUM(ABS(amount)) AS total_amount
                FROM core.transactions
                WHERE user_id = :userId
                  AND transaction_date >= :previousFrom
                  AND transaction_date < :previousTo
                GROUP BY COALESCE(NULLIF(category, ''), 'UNCATEGORIZED')
            )
            SELECT
                COALESCE(c.category, p.category) AS category,
                COALESCE(c.total_amount, 0) AS current_amount,
                COALESCE(p.total_amount, 0) AS previous_amount
            FROM current_period c
            FULL OUTER JOIN previous_period p ON c.category = p.category
            ORDER BY current_amount DESC
        """.trimIndent()

        return jdbcTemplate.query(sql, params) { rs, _ ->
            val currentAmount = rs.getBigDecimal("current_amount") ?: BigDecimal.ZERO
            val previousAmount = rs.getBigDecimal("previous_amount") ?: BigDecimal.ZERO
            val delta = currentAmount.subtract(previousAmount)
            val percent = if (previousAmount.compareTo(BigDecimal.ZERO) == 0) {
                null
            } else {
                delta.divide(previousAmount, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .toDouble()
            }

            CategoryDelta(
                category = rs.getString("category"),
                currentAmount = currentAmount,
                previousAmount = previousAmount,
                deltaAmount = delta,
                deltaPercent = percent
            )
        }
    }

    override fun getTopMerchants(userId: UUID, from: Instant, to: Instant, limit: Int): List<MerchantStat> {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to))
            .addValue("limit", limit.coerceIn(1, 20))

        val sql = """
            SELECT
                COALESCE(NULLIF(merchant_name, ''), 'UNKNOWN_MERCHANT') AS merchant_name,
                COALESCE(SUM(ABS(amount)), 0) AS total_amount,
                COUNT(*) AS tx_count
            FROM core.transactions
            WHERE user_id = :userId
              AND transaction_date >= :from
              AND transaction_date < :to
            GROUP BY COALESCE(NULLIF(merchant_name, ''), 'UNKNOWN_MERCHANT')
            ORDER BY total_amount DESC
            LIMIT :limit
        """.trimIndent()

        return jdbcTemplate.query(sql, params) { rs, _ ->
            MerchantStat(
                merchantName = rs.getString("merchant_name"),
                totalAmount = rs.getBigDecimal("total_amount") ?: BigDecimal.ZERO,
                transactionCount = rs.getInt("tx_count")
            )
        }
    }

    override fun detectSpikes(userId: UUID, from: Instant, to: Instant): List<SpikeInfo> {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to))

        val sql = """
            WITH daily AS (
                SELECT
                    COALESCE(NULLIF(category, ''), 'UNCATEGORIZED') AS category,
                    DATE(transaction_date) AS tx_day,
                    SUM(ABS(amount)) AS daily_total
                FROM core.transactions
                WHERE user_id = :userId
                  AND transaction_date >= :from
                  AND transaction_date < :to
                GROUP BY COALESCE(NULLIF(category, ''), 'UNCATEGORIZED'), DATE(transaction_date)
            ),
            stats AS (
                SELECT
                    category,
                    AVG(daily_total) AS avg_total
                FROM daily
                GROUP BY category
            )
            SELECT
                d.category,
                d.tx_day,
                s.avg_total,
                d.daily_total
            FROM daily d
            JOIN stats s ON d.category = s.category
            WHERE d.daily_total > (s.avg_total * 1.8)
              AND d.daily_total - s.avg_total > 500
            ORDER BY d.daily_total DESC
            LIMIT 10
        """.trimIndent()

        return jdbcTemplate.query(sql, params) { rs, _ ->
            SpikeInfo(
                category = rs.getString("category"),
                date = rs.getDate("tx_day").toLocalDate(),
                baselineAmount = rs.getBigDecimal("avg_total") ?: BigDecimal.ZERO,
                spikeAmount = rs.getBigDecimal("daily_total") ?: BigDecimal.ZERO
            )
        }
    }
}
