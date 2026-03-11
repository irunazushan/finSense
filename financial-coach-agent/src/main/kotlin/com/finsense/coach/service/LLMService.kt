package com.finsense.coach.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.config.AppProperties
import com.finsense.coach.dto.llm.LlmAdviceResult
import com.finsense.coach.logging.LLMLogger
import com.finsense.coach.logging.LlmLogRecord
import com.finsense.coach.util.CoachTools
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service
class LLMService(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val coachTools: CoachTools,
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

    fun modelName(): String = openAiModel

    fun providerName(): String = "openai-compatible"

    fun generateAdvice(
        requestId: UUID,
        userId: UUID,
        periodDays: Int,
        userMessage: String
    ): LlmAdviceResult {
        val client = chatClient
            ?: throw IllegalStateException("ChatClient is not configured; check Spring AI setup")

        val started = System.currentTimeMillis()
        val systemPrompt = llmPromptTemplates.systemPrompt()
        val userPrompt = llmPromptTemplates.renderUserPrompt(
            requestId = requestId,
            userId = userId,
            periodDays = periodDays,
            userMessage = userMessage
        )

        return try {
            val chatResponse = CompletableFuture.supplyAsync {
                client.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(coachTools)
                    .call()
                    .chatResponse()
                    ?: throw IllegalStateException("LLM returned empty ChatResponse")
            }.orTimeout(appProperties.llm.timeoutSeconds, TimeUnit.SECONDS)
                .join()

            val latency = System.currentTimeMillis() - started
            val rawText = chatResponse.result?.output?.text?.trim().orEmpty()
            val usedModel = chatResponse.metadata?.model?.takeIf { it.isNotBlank() } ?: openAiModel
            val totalTokens = extractTotalTokens(chatResponse)

            // Audit full provider response first, parse later.
            llmLogger.log(
                LlmLogRecord(
                    timestamp = java.time.Instant.now(),
                    requestId = requestId,
                    userId = userId,
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

            val parsed = parseAdvice(rawText)
            log.info(
                "LLM completed requestId={} userId={} latencyMs={} configuredModel={} usedModel={} totalTokens={}",
                requestId,
                userId,
                latency,
                openAiModel,
                usedModel,
                totalTokens
            )

            LlmAdviceResult(
                summary = parsed.first,
                advice = parsed.second,
                rawText = rawText,
                usedModel = usedModel,
                totalTokens = totalTokens,
                latencyMs = latency
            )
        } catch (ex: Exception) {
            val latency = System.currentTimeMillis() - started
            llmLogger.log(
                LlmLogRecord(
                    timestamp = java.time.Instant.now(),
                    requestId = requestId,
                    userId = userId,
                    usedModel = openAiModel,
                    configuredModel = null,
                    systemTemplateId = LlmPromptTemplates.SYSTEM_TEMPLATE_ID,
                    userTemplateId = LlmPromptTemplates.USER_TEMPLATE_ID,
                    renderedUserPrompt = userPrompt,
                    rawResponse = null,
                    totalTokens = null,
                    latencyMs = latency,
                    success = false,
                    error = ex.message
                )
            )
            throw ex
        }
    }

    private fun extractTotalTokens(chatResponse: org.springframework.ai.chat.model.ChatResponse): Int? {
        val usage = chatResponse.metadata?.usage ?: return null
        val nativeUsage = usage.nativeUsage
        val hasUsageData = when (nativeUsage) {
            null -> false
            is Map<*, *> -> nativeUsage.isNotEmpty()
            else -> true
        }
        return if (hasUsageData) usage.totalTokens else null
    }

    private fun parseAdvice(raw: String): Pair<String, String> {
        val cleaned = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val tree = objectMapper.readTree(cleaned)
            val summary = tree.path("summary").asText("").ifBlank { fallbackSummary(cleaned) }
            val advice = tree.path("advice").asText("").ifBlank { fallbackAdvice(cleaned) }
            summary to advice
        } catch (_: Exception) {
            fallbackSummary(cleaned) to fallbackAdvice(cleaned)
        }
    }

    private fun fallbackSummary(text: String): String {
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
            ?: "Подготовлен общий анализ расходов за выбранный период."
    }

    private fun fallbackAdvice(text: String): String {
        return text.take(600)
            .ifBlank { "Сократите крупные повторяющиеся расходы и контролируйте категории с наибольшей долей трат." }
    }
}
