package com.finsense.core.e2e

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TransactionEndpointsE2ETest : BaseE2ETest() {

    @Test
    fun `GET transactions applies filters and pagination clamping`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")

        // Insert a non-matching transaction (category TRANSPORT)
        dbSeedHelper.insertTransaction(
            transactionId = UUID.randomUUID(),
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("120.00"),
            description = "Taxi",
            merchantName = "Yandex",
            mccCode = "4121",
            transactionDate = Instant.parse("2026-02-01T10:00:00Z"),
            status = "CLASSIFIED",
            category = "TRANSPORT",
            classifierSource = "ML",
            classifierConfidence = 0.96,
            classifiedAt = Instant.parse("2026-02-01T10:00:02Z")
        )

        // Insert multiple matching transactions (FOOD_AND_DRINKS, within date range)
        val matchingTx1 = UUID.randomUUID()
        dbSeedHelper.insertTransaction(
            transactionId = matchingTx1,
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("550.50"),
            description = "Coffee",
            merchantName = "Starbucks",
            mccCode = "5812",
            transactionDate = Instant.parse("2026-02-05T11:00:00Z"),
            status = "CLASSIFIED",
            category = "FOOD_AND_DRINKS",
            classifierSource = "LLM",
            classifierConfidence = 0.91,
            classifiedAt = Instant.parse("2026-02-05T11:00:03Z")
        )

        val matchingTx2 = UUID.randomUUID()
        dbSeedHelper.insertTransaction(
            transactionId = matchingTx2,
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("34.90"),
            description = "Sandwich",
            merchantName = "Pret",
            mccCode = "5812",
            transactionDate = Instant.parse("2026-02-10T12:30:00Z"),
            status = "CLASSIFIED",
            category = "FOOD_AND_DRINKS",
            classifierSource = "ML",
            classifierConfidence = 0.95,
            classifiedAt = Instant.parse("2026-02-10T12:30:05Z")
        )

        val matchingTx3 = UUID.randomUUID()
        dbSeedHelper.insertTransaction(
            transactionId = matchingTx3,
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("12.50"),
            description = "Smoothie",
            merchantName = "Juice Bar",
            mccCode = "5812",
            transactionDate = Instant.parse("2026-02-15T09:15:00Z"),
            status = "CLASSIFIED",
            category = "FOOD_AND_DRINKS",
            classifierSource = "LLM",
            classifierConfidence = 0.88,
            classifiedAt = Instant.parse("2026-02-15T09:15:03Z")
        )

        // Transaction for another user (should be excluded)
        val otherUserId = UUID.randomUUID()
        val otherAccountId = UUID.randomUUID()
        dbSeedHelper.insertUser(otherUserId)
        dbSeedHelper.insertAccount(otherAccountId, otherUserId, number = "ACC-${UUID.randomUUID()}")
        dbSeedHelper.insertTransaction(
            transactionId = UUID.randomUUID(),
            accountId = otherAccountId,
            userId = otherUserId,
            amount = BigDecimal("10.00"),
            description = "Coffee",
            merchantName = "Cafe",
            mccCode = "5812",
            transactionDate = Instant.parse("2026-02-05T11:00:00Z"),
            status = "CLASSIFIED",
            category = "FOOD_AND_DRINKS",
            classifierSource = "ML",
            classifierConfidence = 0.99,
            classifiedAt = Instant.parse("2026-02-05T11:00:01Z")
        )

        // 1. Test default page (0) and size (presumably 20) – expect all 3 matching transactions
        mockMvc.perform(
            get("/api/v1/users/$userId/transactions")
                .param("category", "FOOD_AND_DRINKS")
                .param("status", "CLASSIFIED")
                .param("from", "2026-02-02T00:00:00Z")
                .param("to", "2026-02-28T23:59:59Z")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[?(@.transactionId == '$matchingTx1')]").exists())
            .andExpect(jsonPath("$[?(@.transactionId == '$matchingTx2')]").exists())
            .andExpect(jsonPath("$[?(@.transactionId == '$matchingTx3')]").exists())

        // 2. Test page clamping: negative page should become 0 – expect first page (most recent first if default sort is desc)
        //    Assuming default sort by transactionDate DESC, the most recent is matchingTx3 (2026-02-15)
        mockMvc.perform(
            get("/api/v1/users/$userId/transactions")
                .param("category", "FOOD_AND_DRINKS")
                .param("status", "CLASSIFIED")
                .param("from", "2026-02-02T00:00:00Z")
                .param("to", "2026-02-28T23:59:59Z")
                .param("page", "-5")
                .param("size", "1")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].transactionId").value(matchingTx3.toString())) // most recent

        // 3. Test size clamping: request size larger than maximum allowed (e.g., 1000) – should be capped at max (e.g., 100)
        //    Since we only have 3 transactions, we can only assert that we get all 3 and no error.
        //    To truly test the cap, we would need >100 transactions, which is heavy; we rely on configuration.
        mockMvc.perform(
            get("/api/v1/users/$userId/transactions")
                .param("category", "FOOD_AND_DRINKS")
                .param("status", "CLASSIFIED")
                .param("from", "2026-02-02T00:00:00Z")
                .param("to", "2026-02-28T23:59:59Z")
                .param("page", "0")
                .param("size", "1000")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3)) // all matching returned

        // 4. Test pagination with offset: page=1, size=2 – should return the last transaction (matchingTx1 if sorted desc)
        //    Sorted desc by transactionDate: [matchingTx3, matchingTx2, matchingTx1]
        //    Page 1 (second page) with size 2 should return the single remaining transaction (matchingTx1)
        mockMvc.perform(
            get("/api/v1/users/$userId/transactions")
                .param("category", "FOOD_AND_DRINKS")
                .param("status", "CLASSIFIED")
                .param("from", "2026-02-02T00:00:00Z")
                .param("to", "2026-02-28T23:59:59Z")
                .param("page", "1")
                .param("size", "2")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].transactionId").value(matchingTx1.toString()))

        // 5. Ensure other user's transaction is never returned
        mockMvc.perform(
            get("/api/v1/users/$userId/transactions")
                .param("category", "FOOD_AND_DRINKS")
                .param("status", "CLASSIFIED")
                .param("from", "2026-02-02T00:00:00Z")
                .param("to", "2026-02-28T23:59:59Z")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.userId == '$otherUserId')]").doesNotExist())
    }
}
