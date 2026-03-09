package com.finsense.coach.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.analytics.AnalyticsSnapshot
import com.finsense.coach.analytics.TransactionAnalyzer
import com.finsense.coach.config.AppProperties
import com.finsense.coach.dto.kafka.CoachRequestEvent
import com.finsense.coach.dto.kafka.CoachResponseEvent
import com.finsense.coach.kafka.CoachResponseProducer
import com.finsense.coach.llm.LLMService
import com.finsense.coach.llm.LlmAdviceResult
import com.finsense.coach.logging.LLMLogger
import com.finsense.coach.logging.LlmLogRecord
import com.finsense.coach.model.RecommendationEntity
import com.finsense.coach.model.RecommendationStatus
import com.finsense.coach.repository.RecommendationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.math.pow

@Service
class CoachService(
    private val recommendationRepository: RecommendationRepository,
    private val transactionAnalyzer: TransactionAnalyzer,
    private val llmService: LLMService,
    private val llmLogger: LLMLogger,
    private val responseProducer: CoachResponseProducer,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun process(event: CoachRequestEvent) {
        val recommendation = recommendationRepository.findById(event.requestId).orElse(null)
        if (recommendation == null) {
            handleMissingRecommendation(event)
            return
        }

        if (recommendation.status == RecommendationStatus.COMPLETED || recommendation.status == RecommendationStatus.FAILED) {
            log.info(
                "Skipping duplicate coach request for terminal recommendation requestId={} status={}",
                event.requestId,
                recommendation.status
            )
            return
        }

        val periodDays = event.parameters.periodDays.coerceIn(1, 365)
        val userMessage = event.parameters.message
        val startedAt = Instant.now()
        val analytics = transactionAnalyzer.analyze(event.userId, periodDays, startedAt)

        if (!analytics.hasData()) {
            val summary = "Недостаточно данных для детального анализа."
            val advice = "У вас пока нет данных для анализа. Совершите больше транзакций и повторите запрос."
            completeRecommendation(
                recommendation = recommendation,
                event = event,
                summary = summary,
                advice = advice,
                analytics = analytics,
                llmTokens = null,
                llmLatencyMs = 0,
                fallbackUsed = true
            )
            return
        }

        if (!llmService.isConfigured()) {
            val fallback = buildDeterministicFallback(analytics, periodDays)
            completeRecommendation(
                recommendation = recommendation,
                event = event,
                summary = fallback.first,
                advice = fallback.second,
                analytics = analytics,
                llmTokens = null,
                llmLatencyMs = 0,
                fallbackUsed = true
            )
            return
        }

        val promptForLog = buildPromptForLog(event)
        val llmResult = runCatchingWithBackoff(3) {
            llmService.generateAdvice(
                requestId = event.requestId,
                userId = event.userId,
                periodDays = periodDays,
                userMessage = userMessage
            )
        }

        llmResult.onSuccess { result ->
            llmLogger.log(
                LlmLogRecord(
                    timestamp = Instant.now(),
                    requestId = event.requestId,
                    userId = event.userId,
                    model = appProperties.llm.model,
                    prompt = promptForLog,
                    response = mapOf("message" to result.rawText),
                    tokens = result.tokens,
                    latencyMs = result.latencyMs,
                    success = true
                )
            )
            completeRecommendation(
                recommendation = recommendation,
                event = event,
                summary = result.summary,
                advice = result.advice,
                analytics = analytics,
                llmTokens = result.tokens,
                llmLatencyMs = result.latencyMs,
                fallbackUsed = false
            )
        }.onFailure { ex ->
            llmLogger.log(
                LlmLogRecord(
                    timestamp = Instant.now(),
                    requestId = event.requestId,
                    userId = event.userId,
                    model = appProperties.llm.model,
                    prompt = promptForLog,
                    response = mapOf("message" to ""),
                    tokens = null,
                    latencyMs = 0,
                    success = false,
                    error = ex.message
                )
            )
            failRecommendation(
                recommendation = recommendation,
                event = event,
                error = "LLM processing failed after retries: ${ex.message ?: "unknown"}"
            )
        }
    }

    private fun handleMissingRecommendation(event: CoachRequestEvent) {
        val error = "Recommendation row not found for requestId=${event.requestId}"
        log.error(error)
        responseProducer.publish(
            CoachResponseEvent(
                requestId = event.requestId,
                userId = event.userId,
                summary = "Не удалось подготовить рекомендацию.",
                advice = "Попробуйте создать запрос ещё раз.",
                completedAt = Instant.now(),
                status = RecommendationStatus.FAILED.name,
                error = error
            )
        )
    }

    private fun completeRecommendation(
        recommendation: RecommendationEntity,
        event: CoachRequestEvent,
        summary: String,
        advice: String,
        analytics: AnalyticsSnapshot,
        llmTokens: Int?,
        llmLatencyMs: Long,
        fallbackUsed: Boolean
    ) {
        val completedAt = Instant.now()
        recommendation.status = RecommendationStatus.COMPLETED
        recommendation.completedAt = completedAt
        recommendation.error = null
        recommendation.adviceData = objectMapper.writeValueAsString(
            mapOf(
                "summary" to summary,
                "advice" to advice,
                "tools" to mapOf(
                    "spendingByCategory" to analytics.spendingByCategory,
                    "monthlyDelta" to analytics.monthlyDelta,
                    "topMerchants" to analytics.topMerchants,
                    "spikes" to analytics.spikes
                ),
                "llm" to mapOf(
                    "provider" to appProperties.llm.provider,
                    "model" to appProperties.llm.model,
                    "tokens" to llmTokens,
                    "latencyMs" to llmLatencyMs,
                    "fallbackUsed" to fallbackUsed
                ),
                "request" to mapOf(
                    "requestId" to event.requestId,
                    "userId" to event.userId,
                    "periodDays" to event.parameters.periodDays,
                    "message" to event.parameters.message,
                    "trigger" to event.trigger
                )
            )
        )
        recommendationRepository.save(recommendation)

        responseProducer.publish(
            CoachResponseEvent(
                requestId = event.requestId,
                userId = event.userId,
                summary = summary,
                advice = advice,
                completedAt = completedAt,
                status = RecommendationStatus.COMPLETED.name
            )
        )
    }

    private fun failRecommendation(
        recommendation: RecommendationEntity,
        event: CoachRequestEvent,
        error: String
    ) {
        val completedAt = Instant.now()
        recommendation.status = RecommendationStatus.FAILED
        recommendation.completedAt = completedAt
        recommendation.error = error.take(2000)
        recommendationRepository.save(recommendation)

        responseProducer.publish(
            CoachResponseEvent(
                requestId = event.requestId,
                userId = event.userId,
                summary = "Не удалось подготовить рекомендацию.",
                advice = "Попробуйте повторить запрос позже.",
                completedAt = completedAt,
                status = RecommendationStatus.FAILED.name,
                error = recommendation.error
            )
        )
    }

    private fun runCatchingWithBackoff(maxAttempts: Int, block: () -> LlmAdviceResult): Result<LlmAdviceResult> {
        var attempt = 1
        var lastFailure: Throwable? = null

        while (attempt <= maxAttempts) {
            try {
                return Result.success(block())
            } catch (ex: Throwable) {
                lastFailure = ex
                log.warn("LLM attempt {} failed: {}", attempt, ex.message)
                if (attempt < maxAttempts) {
                    val backoff = (500.0 * 2.0.pow((attempt - 1).toDouble())).toLong()
                    Thread.sleep(backoff)
                }
            }
            attempt++
        }
        return Result.failure(lastFailure ?: IllegalStateException("Unknown LLM failure"))
    }

    private fun buildPromptForLog(event: CoachRequestEvent): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "requestId" to event.requestId,
                "userId" to event.userId,
                "periodDays" to event.parameters.periodDays,
                "message" to event.parameters.message
            )
        )
    }

    private fun buildDeterministicFallback(analytics: AnalyticsSnapshot, periodDays: Int): Pair<String, String> {
        val topCategory = analytics.spendingByCategory.firstOrNull()
        val topMerchant = analytics.topMerchants.firstOrNull()
        val largestGrowth = analytics.monthlyDelta.maxByOrNull { it.deltaAmount }

        val summary = if (topCategory != null) {
            "За последние $periodDays дней самая затратная категория: ${topCategory.category} (${topCategory.totalAmount})."
        } else {
            "Сформирован базовый анализ расходов за последние $periodDays дней."
        }

        val adviceParts = mutableListOf<String>()
        if (topCategory != null) {
            adviceParts += "Поставьте лимит на категорию ${topCategory.category} и отслеживайте отклонение каждую неделю."
        }
        if (topMerchant != null) {
            adviceParts += "Проверьте траты у мерчанта ${topMerchant.merchantName}: это один из главных источников расходов."
        }
        if (largestGrowth != null && largestGrowth.deltaAmount.toDouble() > 0.0) {
            adviceParts += "Категория ${largestGrowth.category} выросла относительно прошлого периода, пересмотрите покупки в этой группе."
        }
        if (adviceParts.isEmpty()) {
            adviceParts += "Регулярно проверяйте повторяющиеся траты и сокращайте неиспользуемые подписки."
        }

        return summary to adviceParts.joinToString(" ")
    }
}
