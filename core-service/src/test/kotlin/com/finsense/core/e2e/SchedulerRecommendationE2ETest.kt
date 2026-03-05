package com.finsense.core.e2e

import com.finsense.core.service.RecommendationScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

class SchedulerRecommendationE2ETest : BaseE2ETest() {

    @Autowired
    private lateinit var recommendationScheduler: RecommendationScheduler

    @Test
    fun `scheduler publishes scheduled coach request with default parameters`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")

        recommendationScheduler.scheduleRecommendations()

        val record = kafkaProbeHelper.consumeSingle(
            topic = "coach-requests",
            timeout = Duration.ofSeconds(10)
        ) { it.key() == userId.toString() }

        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["userId"].asText()).isEqualTo(userId.toString())
        assertThat(payload["trigger"].asText()).isEqualTo("SCHEDULED")
        assertThat(payload["parameters"]["periodDays"].asInt()).isEqualTo(30)
        assertThat(payload["parameters"]["message"].asText()).isEqualTo("Дай общий совет по экономии за выбранный период.")
    }
}
