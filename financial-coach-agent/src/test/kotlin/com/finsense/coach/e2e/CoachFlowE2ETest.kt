package com.finsense.coach.e2e

import com.finsense.coach.dto.kafka.CoachRequestEvent
import com.finsense.coach.dto.kafka.CoachRequestParameters
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CoachFlowE2ETest : BaseE2ETest() {

    @Test
    fun `consume coach request updates recommendation and emits completed event`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, "ACC-${UUID.randomUUID()}")
        dbSeedHelper.insertTransaction(
            transactionId = UUID.randomUUID(),
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("1200.00"),
            category = "FOOD",
            merchant = "COFFEE SHOP",
            transactionDate = Instant.now().minusSeconds(3600)
        )
        dbSeedHelper.insertPendingRecommendation(requestId, userId)

        val event = CoachRequestEvent(
            requestId = requestId,
            userId = userId,
            trigger = "MANUAL",
            requestedAt = Instant.now(),
            parameters = CoachRequestParameters(30, "Дай совет")
        )
        kafkaTemplate.send("coach-requests", userId.toString(), objectMapper.writeValueAsString(event))

        val record = kafkaProbeHelper.consumeSingle(
            topic = "coach-responses",
            timeout = Duration.ofSeconds(15)
        ) { it.value().contains(requestId.toString()) }
        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["requestId"].asText()).isEqualTo(requestId.toString())
        assertThat(payload["status"].asText()).isEqualTo("COMPLETED")
        assertThat(payload["summary"].asText()).isEqualTo("mock-summary")
        assertThat(payload["advice"].asText()).isEqualTo("mock-advice")

        Thread.sleep(1000)
        val row = dbSeedHelper.recommendationRow(requestId)
        assertThat(row).isNotNull
        assertThat(row!!["status"]).isEqualTo("COMPLETED")
        assertThat(row["advice_data"] as String).contains("tools")
    }

    @Test
    fun `missing recommendation row emits failed event`() {
        val userId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)

        val requestId = UUID.randomUUID()
        val event = CoachRequestEvent(
            requestId = requestId,
            userId = userId,
            trigger = "MANUAL",
            requestedAt = Instant.now(),
            parameters = CoachRequestParameters(30, "Дай совет")
        )
        kafkaTemplate.send("coach-requests", userId.toString(), objectMapper.writeValueAsString(event))

        val record = kafkaProbeHelper.consumeSingle(
            topic = "coach-responses",
            timeout = Duration.ofSeconds(15)
        ) { it.value().contains(requestId.toString()) }
        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["status"].asText()).isEqualTo("FAILED")
        assertThat(payload["error"].asText()).contains("not found")
    }
}
