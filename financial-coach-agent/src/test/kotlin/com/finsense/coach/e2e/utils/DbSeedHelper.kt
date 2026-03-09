package com.finsense.coach.e2e.utils

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class DbSeedHelper(
    private val jdbcTemplate: JdbcTemplate
) {
    fun truncateAll() {
        jdbcTemplate.execute("DELETE FROM recommendations.recommendations")
        jdbcTemplate.execute("DELETE FROM core.transactions")
        jdbcTemplate.execute("DELETE FROM core.accounts")
        jdbcTemplate.execute("DELETE FROM core.users")
    }

    fun insertUser(userId: UUID, email: String = "$userId@finsense.local") {
        jdbcTemplate.update(
            "INSERT INTO core.users (id, email, created_at) VALUES (?, ?, now())",
            userId, email
        )
    }

    fun insertAccount(accountId: UUID, userId: UUID, number: String) {
        jdbcTemplate.update(
            """
            INSERT INTO core.accounts (id, user_id, number, type, currency, created_at)
            VALUES (?, ?, ?, 'debit', 'RUB', now())
            """.trimIndent(),
            accountId, userId, number
        )
    }

    fun insertTransaction(
        transactionId: UUID,
        accountId: UUID,
        userId: UUID,
        amount: BigDecimal,
        category: String,
        merchant: String,
        transactionDate: Instant
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO core.transactions (
                id, account_id, user_id, amount, description, merchant_name, mcc_code,
                transaction_date, status, category
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            transactionId,
            accountId,
            userId,
            amount,
            "desc",
            merchant,
            "5411",
            Timestamp.from(transactionDate),
            "CLASSIFIED",
            category
        )
    }

    fun insertPendingRecommendation(requestId: UUID, userId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO recommendations.recommendations (
                id, user_id, created_at, status, request_params
            ) VALUES (?, ?, now(), 'PENDING', ?::jsonb)
            """.trimIndent(),
            requestId,
            userId,
            """{"periodDays":30,"message":"Дай общий совет"}"""
        )
    }

    fun recommendationRow(requestId: UUID): Map<String, Any?>? {
        val rows = jdbcTemplate.queryForList(
            """
            SELECT id, status, advice_data::text AS advice_data, error, completed_at
            FROM recommendations.recommendations
            WHERE id = ?
            """.trimIndent(),
            requestId
        )
        return rows.firstOrNull()
    }
}
