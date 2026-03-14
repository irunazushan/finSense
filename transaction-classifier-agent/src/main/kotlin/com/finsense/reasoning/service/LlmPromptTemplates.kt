package com.finsense.reasoning.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

@Component
class LlmPromptTemplates(
    resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
    private val appProperties: AppProperties
) {
    companion object {
        const val SYSTEM_TEMPLATE_ID = "classifier-system-v1"
        const val USER_TEMPLATE_ID = "classifier-user-v1"
    }

    private val systemPromptTemplate = resourceLoader
        .getResource("classpath:prompts/$SYSTEM_TEMPLATE_ID.txt")
        .inputStream.bufferedReader().use { it.readText() }

    private val userPromptTemplate = resourceLoader
        .getResource("classpath:prompts/$USER_TEMPLATE_ID.txt")
        .inputStream.bufferedReader().use { it.readText() }

    fun systemPrompt(): String = systemPromptTemplate

    fun renderUserPrompt(event: LlmClassifierRequestEvent): String {
        val payload = mapOf(
            "requestId" to event.requestId,
            "transactionId" to event.transactionId,
            "occurredAt" to event.occurredAt,
            "predictedCategory" to event.predictedCategory,
            "mlConfidence" to event.confidence,
            "transaction" to event.transaction,
            "history" to event.history
        )

        return userPromptTemplate
            .replace("{{allowedCategories}}", appProperties.llm.normalizedAllowedCategories().joinToString(", "))
            .replace("{{requestPayload}}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
    }
}
