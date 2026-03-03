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
        val matchingTx = UUID.randomUUID()
        dbSeedHelper.insertTransaction(
            transactionId = matchingTx,
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

        mockMvc.perform(
            get(
                "/api/v1/users/$userId/transactions" +
                    "?category=FOOD_AND_DRINKS&status=CLASSIFIED" +
                    "&from=2026-02-02T00:00:00Z&to=2026-02-28T23:59:59Z&page=-5&size=1000"
            )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].transactionId").value(matchingTx.toString()))
            .andExpect(jsonPath("$[0].userId").value(userId.toString()))
            .andExpect(jsonPath("$[0].category").value("FOOD_AND_DRINKS"))
            .andExpect(jsonPath("$[0].status").value("CLASSIFIED"))
    }
}
