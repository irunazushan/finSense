package com.finsense.coach.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class LlmAdviceResult(
    val summary: String,
    val advice: String,
    val rawText: String,
    val tokens: Int?,
    val latencyMs: Long
)

@Service
class LLMService(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val coachTools: CoachTools,
    chatClientBuilderProvider: ObjectProvider<ChatClient.Builder>
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val chatClient: ChatClient? = chatClientBuilderProvider.getIfAvailable()?.build()

    fun isConfigured(): Boolean = appProperties.llm.apiKey.isNotBlank()

    fun generateAdvice(
        requestId: UUID,
        userId: UUID,
        periodDays: Int,
        userMessage: String
    ): LlmAdviceResult {
        val client = chatClient
            ?: throw IllegalStateException("ChatClient is not configured; check Spring AI setup")

        val prompt = buildPrompt(
            requestId = requestId,
            userId = userId,
            periodDays = periodDays,
            userMessage = userMessage
        )

        val started = System.currentTimeMillis()
        val text = CompletableFuture.supplyAsync {
            client.prompt()
                .user(prompt)
                .tools(coachTools)
                .call()
                .content()
                ?.trim()
                .orEmpty()
        }.orTimeout(appProperties.llm.timeoutSeconds, TimeUnit.SECONDS)
            .join()
        val latency = System.currentTimeMillis() - started

        val parsed = parseAdvice(text)
        log.info(
            "LLM completed requestId={} userId={} latencyMs={} configuredModel={}",
            requestId,
            userId,
            latency,
            appProperties.llm.model
        )

        return LlmAdviceResult(
            summary = parsed.first,
            advice = parsed.second,
            rawText = text,
            tokens = null,
            latencyMs = latency
        )
    }

    private fun buildPrompt(
        requestId: UUID,
        userId: UUID,
        periodDays: Int,
        userMessage: String
    ): String {
        return """
            Ты финансовый коуч. Работай в режиме tool-calling.
            Перед ответом вызови доступные tools для userId и periodDays:
            - getSpendingByCategory
            - getMonthlyDelta
            - getTopMerchants
            - detectSpikes
            Не выдумывай факты, используй данные только из результатов tool-вызовов.
            Ответ строго в JSON-объекте формата:
            {"summary":"<1-2 предложения>","advice":"<конкретные действия>"}
            
            requestId: $requestId
            userId: $userId
            periodDays: $periodDays
            userMessage: $userMessage
            generatedAt: ${Instant.now()}
        """.trimIndent()
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
        return text.take(600).ifBlank { "Сократите крупные повторяющиеся расходы и контролируйте категории с наибольшей долей трат." }
    }
}
