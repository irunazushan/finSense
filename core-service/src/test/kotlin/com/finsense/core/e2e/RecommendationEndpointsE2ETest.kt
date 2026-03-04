package com.finsense.core.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

class RecommendationEndpointsE2ETest : BaseE2ETest() {

    @Test
    fun `POST recommendations creates pending row and emits coach-request`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")

        val mvcResult = mockMvc.perform(
            post("/api/v1/users/$userId/recommendations")
                .contentType("application/json")
                .content("""{"parameters":{"periodDays":30,"message": "На какой категорий я мог бы сэкономить больше всего?"}}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()

        val json = objectMapper.readTree(mvcResult.response.contentAsString)
        val requestId = UUID.fromString(json["requestId"].asText())

        val rowCount = dbSeedHelper.countRecommendationsByIdAndStatus(requestId, userId, "PENDING")
        assertThat(rowCount).isEqualTo(1)

        val record = kafkaProbeHelper.consumeSingle(
            topic = "coach-requests",
            timeout = Duration.ofSeconds(10)
        ) { it.value().contains(requestId.toString()) }

        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["requestId"].asText()).isEqualTo(requestId.toString())
        assertThat(payload["userId"].asText()).isEqualTo(userId.toString())
        assertThat(payload["trigger"].asText()).isEqualTo("MANUAL")
    }

    @Test
    fun `GET recommendation by id returns row when exists and 404 when missing`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val recommendationId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")
        dbSeedHelper.insertRecommendation(
            recommendationId = recommendationId,
            userId = userId,
            status = "COMPLETED",
            createdAt = Instant.parse("2026-02-01T00:00:00Z"),
            completedAt = Instant.parse("2026-02-01T00:01:00Z"),
            adviceDataJson = """{"summary":"cut subscriptions"}""",
            requestParamsJson = """{"periodDays":30}"""
        )

        mockMvc.perform(get("/api/v1/recommendations/$recommendationId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.requestId").value(recommendationId.toString()))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.adviceData.summary").value("cut subscriptions"))

        mockMvc.perform(get("/api/v1/recommendations/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET user recommendations returns only completed sorted desc and respects limit`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")

        dbSeedHelper.insertRecommendation(
            recommendationId = UUID.randomUUID(),
            userId = userId,
            status = "FAILED",
            createdAt = Instant.parse("2026-02-01T00:00:00Z"),
            error = "timeout"
        )
        dbSeedHelper.insertRecommendation(
            recommendationId = UUID.randomUUID(),
            userId = userId,
            status = "COMPLETED",
            createdAt = Instant.parse("2026-02-02T00:00:00Z"),
            adviceDataJson = """{"summary":"older"}"""
        )
        val newestId = UUID.randomUUID()
        dbSeedHelper.insertRecommendation(
            recommendationId = newestId,
            userId = userId,
            status = "COMPLETED",
            createdAt = Instant.parse("2026-02-03T00:00:00Z"),
            adviceDataJson = """{"summary":"newest"}"""
        )

        mockMvc.perform(get("/api/v1/users/$userId/recommendations?limit=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].requestId").value(newestId.toString()))
            .andExpect(jsonPath("$[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$[0].adviceData.summary").value("newest"))
    }
}
