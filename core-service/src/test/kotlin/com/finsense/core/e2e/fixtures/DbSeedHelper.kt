package com.finsense.core.e2e.fixtures

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
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

    fun insertUser(userId: UUID, email: String = "$userId@finsense.local", createdAt: Instant = Instant.now()) {
        jdbcTemplate.update(
            "INSERT INTO core.users (id, email, created_at) VALUES (?, ?, ?)",
            userId,
            email,
            Timestamp.from(createdAt)
        )
    }

    fun insertAccount(
        accountId: UUID,
        userId: UUID,
        number: String,
        type: String = "debit",
        currency: String = "RUB",
        createdAt: Instant = Instant.now()
    ) {
        jdbcTemplate.update(
            "INSERT INTO core.accounts (id, user_id, number, type, currency, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            accountId,
            userId,
            number,
            type,
            currency,
            Timestamp.from(createdAt)
        )
    }

    fun insertTransaction(
        transactionId: UUID,
        accountId: UUID,
        userId: UUID,
        amount: BigDecimal,
        description: String?,
        merchantName: String?,
        mccCode: String?,
        transactionDate: Instant,
        status: String,
        category: String?,
        classifierSource: String?,
        classifierConfidence: Double?,
        classifiedAt: Instant?
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO core.transactions (
                id, account_id, user_id, amount, description, merchant_name, mcc_code,
                transaction_date, status, category, classifier_source, classifier_confidence, classified_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            transactionId,
            accountId,
            userId,
            amount,
            description,
            merchantName,
            mccCode,
            Timestamp.from(transactionDate),
            status,
            category,
            classifierSource,
            classifierConfidence,
            classifiedAt?.let { Timestamp.from(it) }
        )
    }

    fun insertRecommendation(
        recommendationId: UUID,
        userId: UUID,
        status: String,
        createdAt: Instant,
        completedAt: Instant? = null,
        adviceDataJson: String? = null,
        requestParamsJson: String? = null,
        error: String? = null
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO recommendations.recommendations (
                id, user_id, created_at, status, completed_at, advice_data, request_params, error
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """.trimIndent(),
            recommendationId,
            userId,
            Timestamp.from(createdAt),
            status,
            completedAt?.let { Timestamp.from(it) },
            adviceDataJson,
            requestParamsJson,
            error
        )
    }

    fun countRecommendationsByIdAndStatus(
        recommendationId: UUID,
        userId: UUID,
        status: String
    ): Long {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recommendations.recommendations WHERE id = ? AND user_id = ? AND status = ?",
            Long::class.java,
            recommendationId,
            userId,
            status
        ) ?: 0L
    }

    fun transactionRow(transactionId: UUID): Map<String, Any?>? {
        return jdbcTemplate.query(
            """
            SELECT id, status, category, classifier_source, classifier_confidence, classified_at
            FROM core.transactions
            WHERE id = ?
            """.trimIndent(),
            ResultSetExtractor { rs ->
                if (rs.next()) {
                    mapOf(
                        "id" to rs.getObject("id"),
                        "status" to rs.getString("status"),
                        "category" to rs.getString("category"),
                        "classifier_source" to rs.getString("classifier_source"),
                        "classifier_confidence" to rs.getObject("classifier_confidence"),
                        "classified_at" to rs.getTimestamp("classified_at")
                    )
                } else {
                    null
                }
            },
            transactionId
        )
    }

    fun countTransactionsById(transactionId: UUID): Long {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM core.transactions WHERE id = ?",
            Long::class.java,
            transactionId
        ) ?: 0L
    }
}
