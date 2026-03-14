package com.finsense.reasoning.e2e

import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.kafka.TransactionContext
import com.finsense.reasoning.dto.llm.LlmClassificationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ClassificationFlowE2ETest : BaseE2ETest() {

    @Test
    fun `consumes request and publishes classified response`() {
        val event = sampleEvent()
        kafkaTemplate.send("llm-classifier-requests", event.transactionId.toString(), objectMapper.writeValueAsString(event)).get()

        val record = kafkaProbeHelper.consumeSingle(
            topic = "llm-classifier-responses",
            timeout = Duration.ofSeconds(15)
        ) { it.value().contains(event.transactionId.toString()) }

        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["transactionId"].asText()).isEqualTo(event.transactionId.toString())
        assertThat(payload["requestId"].asText()).isEqualTo(event.requestId.toString())
        assertThat(payload["category"].asText()).isEqualTo("TRANSPORT")
        assertThat(payload["confidence"].asDouble()).isEqualTo(0.93)
    }

    @Test
    fun `retries once and then publishes classified response`() {
        doReturn(true).`when`(llmService).isConfigured()
        org.mockito.Mockito.`when`(llmService.classify(any()))
            .thenThrow(IllegalStateException("first failure"))
            .thenReturn(
                LlmClassificationResult(
                    category = "SHOPPING",
                    confidence = 0.82,
                    reasoning = "merchant match",
                    rawText = """{"category":"SHOPPING","confidence":0.82}""",
                    usedModel = "deepseek-chat",
                    totalTokens = 10,
                    latencyMs = 20
                )
            )
        val event = sampleEvent()

        kafkaTemplate.send("llm-classifier-requests", event.transactionId.toString(), objectMapper.writeValueAsString(event)).get()

        val record = kafkaProbeHelper.consumeSingle(
            topic = "llm-classifier-responses",
            timeout = Duration.ofSeconds(15)
        ) { it.value().contains(event.transactionId.toString()) }

        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["category"].asText()).isEqualTo("SHOPPING")
        assertThat(payload["confidence"].asDouble()).isEqualTo(0.82)
        verify(llmService, times(2)).classify(any())
    }

    @Test
    fun `publishes undefined fallback after repeated llm failure`() {
        doReturn(true).`when`(llmService).isConfigured()
        doThrow(IllegalStateException("timeout")).`when`(llmService).classify(any())
        val event = sampleEvent()

        kafkaTemplate.send("llm-classifier-requests", event.transactionId.toString(), objectMapper.writeValueAsString(event)).get()

        val record = kafkaProbeHelper.consumeSingle(
            topic = "llm-classifier-responses",
            timeout = Duration.ofSeconds(15)
        ) { it.value().contains(event.transactionId.toString()) }

        assertThat(record).isNotNull
        val payload = objectMapper.readTree(record!!.value())
        assertThat(payload["category"].asText()).isEqualTo("UNDEFINED")
        assertThat(payload["confidence"].asDouble()).isEqualTo(0.0)
        verify(llmService, times(3)).classify(any())
    }

    private fun sampleEvent(): LlmClassifierRequestEvent {
        return LlmClassifierRequestEvent(
            requestId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-02-10T10:15:30Z"),
            transaction = TransactionContext(
                userId = UUID.randomUUID(),
                amount = BigDecimal("1250.75"),
                description = "Coffee Starbucks",
                merchantName = "Starbucks",
                mccCode = "5812",
                transactionDate = Instant.parse("2026-02-17T12:34:56Z")
            ),
            confidence = 0.62,
            predictedCategory = "TRANSPORT",
            history = emptyList()
        )
    }
}
