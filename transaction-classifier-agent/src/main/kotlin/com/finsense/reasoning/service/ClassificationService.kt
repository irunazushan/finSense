package com.finsense.reasoning.service

import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.kafka.LlmClassifierResponseEvent
import com.finsense.reasoning.kafka.ClassifierResponseProducer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClassificationService(
    private val llmService: LLMService,
    private val responseProducer: ClassifierResponseProducer,
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(event: LlmClassifierRequestEvent) {
        if (!llmService.isConfigured()) {
            log.warn(
                "LLM is not configured, publishing fallback response transactionId={} requestId={}",
                event.transactionId,
                event.requestId
            )
            responseProducer.publish(fallbackResponse(event))
            return
        }

        val maxAttempts = appProperties.llm.retry.maxAttempts.coerceAtLeast(1)
        val backoffDelayMs = appProperties.llm.retry.backoffDelayMs.coerceAtLeast(0)
        var attempt = 1
        var lastFailure: Throwable? = null

        while (attempt <= maxAttempts) {
            try {
                val result = llmService.classify(event)
                responseProducer.publish(
                    LlmClassifierResponseEvent(
                        requestId = event.requestId,
                        transactionId = event.transactionId,
                        category = result.category,
                        confidence = result.confidence,
                        processedAt = Instant.now()
                    )
                )
                return
            } catch (ex: Throwable) {
                lastFailure = ex
                log.warn(
                    "LLM classification attempt {} failed for transactionId={} requestId={}: {}",
                    attempt,
                    event.transactionId,
                    event.requestId,
                    ex.message
                )
                if (attempt < maxAttempts && backoffDelayMs > 0) {
                    try {
                        Thread.sleep(backoffDelayMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        lastFailure = interrupted
                        break
                    }
                }
            }
            attempt++
        }

        log.error(
            "Publishing fallback response after retry exhaustion transactionId={} requestId={} lastError={}",
            event.transactionId,
            event.requestId,
            lastFailure?.message
        )
        responseProducer.publish(fallbackResponse(event))
    }

    private fun fallbackResponse(event: LlmClassifierRequestEvent): LlmClassifierResponseEvent {
        return LlmClassifierResponseEvent(
            requestId = event.requestId,
            transactionId = event.transactionId,
            category = "UNDEFINED",
            confidence = 0.0,
            processedAt = Instant.now()
        )
    }
}
