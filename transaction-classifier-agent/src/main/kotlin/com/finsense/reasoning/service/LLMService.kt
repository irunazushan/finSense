package com.finsense.reasoning.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.llm.LlmClassificationResult
import com.finsense.reasoning.logging.LLMLogger
import com.finsense.reasoning.logging.LlmLogRecord
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service
class LLMService(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val llmPromptTemplates: LlmPromptTemplates,
    private val llmLogger: LLMLogger,
    chatClientBuilderProvider: ObjectProvider<ChatClient.Builder>
) {
    @Value("\${spring.ai.openai.api-key:}")
    private lateinit var openAiApiKey: String

    @Value("\${spring.ai.openai.chat.options.model:deepseek-chat}")
    private lateinit var openAiModel: String

    private val log = LoggerFactory.getLogger(javaClass)
    private val chatClient: ChatClient? = chatClientBuilderProvider.getIfAvailable()?.build()

    fun isConfigured(): Boolean = openAiApiKey.isNotBlank() && openAiApiKey != "test-key-for-disabled-llm"

    fun classify(event: LlmClassifierRequestEvent): LlmClassificationResult {
        val client = chatClient
            ?: throw IllegalStateException("ChatClient is not configured; check Spring AI setup")

        val started = System.currentTimeMillis()
        val systemPrompt = llmPromptTemplates.systemPrompt()
        val userPrompt = llmPromptTemplates.renderUserPrompt(event)
        var chatResponse: ChatResponse? = null

        try {
            chatResponse = CompletableFuture.supplyAsync {
                client.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse()
                    ?: throw IllegalStateException("LLM returned empty ChatResponse")
            }.orTimeout(appProperties.llm.timeoutSeconds, TimeUnit.SECONDS)
                .join()

            val latency = System.currentTimeMillis() - started
            val rawText = chatResponse.result?.output?.text?.trim().orEmpty()
            val usedModel = chatResponse.metadata?.model?.takeIf { it.isNotBlank() } ?: openAiModel
            val totalTokens = chatResponse.metadata.usage.totalTokens
            val parsed = parseClassification(rawText, appProperties.llm.normalizedAllowedCategories())

            llmLogger.log(
                LlmLogRecord(
                    timestamp = Instant.now(),
                    requestId = event.requestId,
                    transactionId = event.transactionId,
                    userId = event.transaction.userId,
                    usedModel = usedModel,
                    configuredModel = openAiModel.takeIf { it != usedModel },
                    systemTemplateId = LlmPromptTemplates.SYSTEM_TEMPLATE_ID,
                    userTemplateId = LlmPromptTemplates.USER_TEMPLATE_ID,
                    renderedUserPrompt = userPrompt,
                    rawResponse = chatResponse,
                    totalTokens = totalTokens,
                    latencyMs = latency,
                    success = true
                )
            )

            log.info(
                "LLM classified transactionId={} requestId={} category={} confidence={} latencyMs={} model={}",
                event.transactionId,
                event.requestId,
                parsed.category,
                parsed.confidence,
                latency,
                usedModel
            )

            return parsed.copy(
                rawText = rawText,
                usedModel = usedModel,
                totalTokens = totalTokens,
                latencyMs = latency
            )
        } catch (ex: Exception) {
            val latency = System.currentTimeMillis() - started
            llmLogger.log(
                LlmLogRecord(
                    timestamp = Instant.now(),
                    requestId = event.requestId,
                    transactionId = event.transactionId,
                    userId = event.transaction.userId,
                    usedModel = openAiModel,
                    configuredModel = null,
                    systemTemplateId = LlmPromptTemplates.SYSTEM_TEMPLATE_ID,
                    userTemplateId = LlmPromptTemplates.USER_TEMPLATE_ID,
                    renderedUserPrompt = userPrompt,
                    rawResponse = chatResponse,
                    totalTokens = chatResponse?.metadata?.usage?.totalTokens,
                    latencyMs = latency,
                    success = false,
                    error = ex.message
                )
            )
            throw ex
        }
    }

    private fun parseClassification(raw: String, allowedCategories: Set<String>): LlmClassificationResult {
        val cleaned = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.isBlank()) {
            throw IllegalArgumentException("LLM returned blank classification payload")
        }

        val tree = objectMapper.readTree(cleaned)
        val category = tree.path("category").asText("").trim().uppercase()
        if (category.isBlank()) {
            throw IllegalArgumentException("LLM response is missing category")
        }
        if (category !in allowedCategories) {
            throw IllegalArgumentException("LLM returned unsupported category: $category")
        }

        val confidenceNode = tree.path("confidence")
        if (!confidenceNode.isNumber) {
            throw IllegalArgumentException("LLM response confidence must be numeric")
        }
        val confidence = confidenceNode.asDouble()
        if (confidence < 0.0 || confidence > 1.0) {
            throw IllegalArgumentException("LLM response confidence must be in range 0.0..1.0")
        }

        return LlmClassificationResult(
            category = category,
            confidence = confidence,
            reasoning = tree.path("reasoning").asText("").trim(),
            rawText = cleaned,
            usedModel = "",
            totalTokens = null,
            latencyMs = 0
        )
    }
}
