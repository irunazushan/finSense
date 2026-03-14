package com.finsense.reasoning.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.dto.kafka.HistoryTransaction
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.kafka.TransactionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class LlmPromptTemplatesTest {

    private val templates = LlmPromptTemplates(
        resourceLoader = DefaultResourceLoader(),
        objectMapper = ObjectMapper().registerModule(JavaTimeModule()),
        appProperties = AppProperties()
    )

    @Test
    fun `renders prompt with categories and request payload`() {
        val event = LlmClassifierRequestEvent(
            requestId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-02-10T10:15:30Z"),
            transaction = TransactionContext(
                userId = UUID.randomUUID(),
                amount = BigDecimal("1250.75"),
                description = "Coffee",
                merchantName = "Starbucks",
                mccCode = "5812",
                transactionDate = Instant.parse("2026-02-10T10:10:00Z")
            ),
            confidence = 0.62,
            predictedCategory = "TRANSPORT",
            history = listOf(
                HistoryTransaction(
                    transactionId = UUID.randomUUID(),
                    amount = BigDecimal("200.00"),
                    category = "FOOD_AND_DRINKS",
                    merchantName = "Starbucks",
                    transactionDate = Instant.parse("2026-02-09T10:10:00Z")
                )
            )
        )

        val prompt = templates.renderUserPrompt(event)

        assertThat(templates.systemPrompt()).contains("Return JSON only")
        assertThat(prompt).contains("FOOD_AND_DRINKS")
        assertThat(prompt).contains(event.transactionId.toString())
        assertThat(prompt).contains("TRANSPORT")
        assertThat(prompt).contains("Starbucks")
    }
}
