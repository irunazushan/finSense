package com.finsense.reasoning.service

import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.config.LlmProperties
import com.finsense.reasoning.config.RetryProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.kafka.LlmClassifierResponseEvent
import com.finsense.reasoning.dto.kafka.TransactionContext
import com.finsense.reasoning.dto.llm.LlmClassificationResult
import com.finsense.reasoning.kafka.ClassifierResponseProducer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ClassificationServiceTest {

    @Mock
    private lateinit var llmService: LLMService

    @Mock
    private lateinit var responseProducer: ClassifierResponseProducer

    @Test
    fun `retries and publishes successful response`() {
        val event = sampleEvent()
        whenever(llmService.isConfigured()).thenReturn(true)
        whenever(llmService.classify(event))
            .doThrow(IllegalArgumentException("bad json"))
            .doReturn(
                LlmClassificationResult(
                    category = "SHOPPING",
                    confidence = 0.88,
                    reasoning = "merchant match",
                    rawText = "{}",
                    usedModel = "deepseek-chat",
                    totalTokens = 12,
                    latencyMs = 100
                )
            )

        val service = ClassificationService(
            llmService = llmService,
            responseProducer = responseProducer,
            appProperties = AppProperties(llm = LlmProperties(retry = RetryProperties(maxAttempts = 3, backoffDelayMs = 0)))
        )

        service.process(event)

        verify(llmService, times(2)).classify(event)
        val captor = argumentCaptor<LlmClassifierResponseEvent>()
        verify(responseProducer).publish(captor.capture())
        assertThat(captor.firstValue.category).isEqualTo("SHOPPING")
        assertThat(captor.firstValue.confidence).isEqualTo(0.88)
    }

    @Test
    fun `publishes undefined fallback after retry exhaustion`() {
        val event = sampleEvent()
        whenever(llmService.isConfigured()).thenReturn(true)
        whenever(llmService.classify(event))
            .thenThrow(IllegalStateException("timeout"))

        val service = ClassificationService(
            llmService = llmService,
            responseProducer = responseProducer,
            appProperties = AppProperties(llm = LlmProperties(retry = RetryProperties(maxAttempts = 3, backoffDelayMs = 0)))
        )

        service.process(event)

        verify(llmService, times(3)).classify(event)
        val captor = argumentCaptor<LlmClassifierResponseEvent>()
        verify(responseProducer).publish(captor.capture())
        assertThat(captor.firstValue.category).isEqualTo("UNDEFINED")
        assertThat(captor.firstValue.confidence).isEqualTo(0.0)
    }

    private fun sampleEvent(): LlmClassifierRequestEvent {
        return LlmClassifierRequestEvent(
            requestId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            occurredAt = Instant.now(),
            transaction = TransactionContext(
                userId = UUID.randomUUID(),
                amount = BigDecimal("10.00"),
                description = "unknown",
                merchantName = "merchant",
                mccCode = null,
                transactionDate = Instant.now()
            ),
            confidence = 0.41,
            predictedCategory = "OTHER",
            history = emptyList()
        )
    }
}
