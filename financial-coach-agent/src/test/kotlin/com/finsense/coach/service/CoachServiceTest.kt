package com.finsense.coach.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.finsense.coach.config.*
import com.finsense.coach.dto.kafka.CoachRequestEvent
import com.finsense.coach.dto.kafka.CoachRequestParameters
import com.finsense.coach.dto.kafka.CoachResponseEvent
import com.finsense.coach.dto.llm.LlmAdviceResult
import com.finsense.coach.kafka.CoachResponseProducer
import com.finsense.coach.logging.LLMLogger
import com.finsense.coach.model.*
import com.finsense.coach.repository.RecommendationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

@ExtendWith(MockitoExtension::class)
class CoachServiceTest {

    @Mock
    private lateinit var recommendationRepository: RecommendationRepository

    @Mock
    private lateinit var transactionAnalyzer: TransactionAnalyzerService

    @Mock
    private lateinit var llmService: LLMService

    @Mock
    private lateinit var llmLogger: LLMLogger

    @Mock
    private lateinit var responseProducer: CoachResponseProducer

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
    private lateinit var appProperties: AppProperties

    private lateinit var coachService: CoachService

    @BeforeEach
    fun setUp() {
        appProperties = AppProperties(
            llm = LlmProperties(timeoutSeconds = 15),
            kafka = KafkaProperties(TopicProperties("coach-requests", "coach-responses")),
            logging = LoggingProperties("/tmp")
        )
        coachService = CoachService(
            recommendationRepository = recommendationRepository,
            transactionAnalyzer = transactionAnalyzer,
            llmService = llmService,
            llmLogger = llmLogger,
            responseProducer = responseProducer,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `success path marks recommendation completed and publishes event`() {
        val event = coachRequestEvent()
        val recommendation = pendingRecommendation(event.requestId, event.userId)
        val analytics = nonEmptyAnalytics()

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.of(recommendation))
        whenever(transactionAnalyzer.analyze(eq(event.userId), eq(30), any())).thenReturn(analytics)
        whenever(llmService.isConfigured()).thenReturn(true)
        whenever(llmService.modelName()).thenReturn("deepseek-chat")
        whenever(llmService.providerName()).thenReturn("openai-compatible")
        whenever(
            llmService.generateAdvice(
                eq(event.requestId),
                eq(event.userId),
                eq(30),
                eq(event.parameters.message)
            )
        ).thenReturn(
            LlmAdviceResult(
                summary = "summary",
                advice = "advice",
                rawText = """{"summary":"summary","advice":"advice"}""",
                tokens = 123,
                latencyMs = 50
            )
        )

        coachService.process(event)

        assertThat(recommendation.status).isEqualTo(RecommendationStatus.COMPLETED)
        assertThat(recommendation.completedAt).isNotNull()
        assertThat(recommendation.error).isNull()
        assertThat(recommendation.adviceData).contains("summary")
        verify(recommendationRepository).save(recommendation)
        val responseCaptor = argumentCaptor<CoachResponseEvent>()
        verify(responseProducer).publish(responseCaptor.capture())
        assertThat(responseCaptor.firstValue.status).isEqualTo("COMPLETED")
        assertThat(responseCaptor.firstValue.summary).isEqualTo("summary")
        assertThat(responseCaptor.firstValue.advice).isEqualTo("advice")
    }

    @Test
    fun `missing llm key uses deterministic fallback and completes`() {
        val event = coachRequestEvent()
        val recommendation = pendingRecommendation(event.requestId, event.userId)
        val analytics = nonEmptyAnalytics()

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.of(recommendation))
        whenever(transactionAnalyzer.analyze(eq(event.userId), eq(30), any())).thenReturn(analytics)
        whenever(llmService.isConfigured()).thenReturn(false)

        coachService.process(event)

        assertThat(recommendation.status).isEqualTo(RecommendationStatus.COMPLETED)
        assertThat(recommendation.adviceData).contains("fallbackUsed")
        verify(llmService, never()).generateAdvice(any(), any(), anyInt(), any())
        val responseCaptor = argumentCaptor<CoachResponseEvent>()
        verify(responseProducer).publish(responseCaptor.capture())
        assertThat(responseCaptor.firstValue.status).isEqualTo("COMPLETED")
    }

    @Test
    fun `no transactions path returns no data advice and completes`() {
        val event = coachRequestEvent()
        val recommendation = pendingRecommendation(event.requestId, event.userId)
        val analytics = AnalyticsSnapshot(emptyList(), emptyList(), emptyList(), emptyList())

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.of(recommendation))
        whenever(transactionAnalyzer.analyze(eq(event.userId), eq(30), any())).thenReturn(analytics)

        coachService.process(event)

        assertThat(recommendation.status).isEqualTo(RecommendationStatus.COMPLETED)
        val adviceJson = objectMapper.readTree(recommendation.adviceData)
        assertThat(adviceJson["advice"].asText()).containsIgnoringCase("нет данных")
        val responseCaptor = argumentCaptor<CoachResponseEvent>()
        verify(responseProducer).publish(responseCaptor.capture())
        assertThat(responseCaptor.firstValue.status).isEqualTo("COMPLETED")
    }

    @Test
    fun `missing recommendation row publishes failed event`() {
        val event = coachRequestEvent()

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.empty())

        coachService.process(event)

        verify(recommendationRepository, never()).save(any())
        val responseCaptor = argumentCaptor<CoachResponseEvent>()
        verify(responseProducer).publish(responseCaptor.capture())
        assertThat(responseCaptor.firstValue.status).isEqualTo("FAILED")
        assertThat(responseCaptor.firstValue.error).contains("not found")
    }

    @Test
    fun `llm retries exhausted marks failed and publishes failed event`() {
        val event = coachRequestEvent()
        val recommendation = pendingRecommendation(event.requestId, event.userId)
        val analytics = nonEmptyAnalytics()

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.of(recommendation))
        whenever(transactionAnalyzer.analyze(eq(event.userId), eq(30), any())).thenReturn(analytics)
        whenever(llmService.isConfigured()).thenReturn(true)
        whenever(llmService.modelName()).thenReturn("deepseek-chat")
        whenever(
            llmService.generateAdvice(
                eq(event.requestId),
                eq(event.userId),
                eq(30),
                eq(event.parameters.message)
            )
        ).thenThrow(RuntimeException("network timeout"))

        coachService.process(event)

        assertThat(recommendation.status).isEqualTo(RecommendationStatus.FAILED)
        assertThat(recommendation.error).contains("LLM processing failed")
        verify(llmService, times(3)).generateAdvice(any(), any(), anyInt(), any())
        val responseCaptor = argumentCaptor<CoachResponseEvent>()
        verify(responseProducer).publish(responseCaptor.capture())
        assertThat(responseCaptor.firstValue.status).isEqualTo("FAILED")
    }

    @Test
    fun `replayed message for terminal recommendation is skipped`() {
        val event = coachRequestEvent()
        val recommendation = pendingRecommendation(event.requestId, event.userId)
        recommendation.status = RecommendationStatus.COMPLETED

        whenever(recommendationRepository.findById(event.requestId)).thenReturn(Optional.of(recommendation))

        coachService.process(event)

        verify(transactionAnalyzer, never()).analyze(any(), anyInt(), any())
        verifyNoInteractions(responseProducer)
        verify(recommendationRepository, never()).save(any())
    }

    private fun coachRequestEvent(): CoachRequestEvent {
        return CoachRequestEvent(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            trigger = "MANUAL",
            requestedAt = Instant.now(),
            parameters = CoachRequestParameters(
                periodDays = 30,
                message = "Дай совет"
            )
        )
    }

    private fun pendingRecommendation(requestId: UUID, userId: UUID): RecommendationEntity {
        return RecommendationEntity(
            id = requestId,
            userId = userId,
            createdAt = Instant.now(),
            status = RecommendationStatus.PENDING,
            requestParams = """{"periodDays":30,"message":"Дай совет"}"""
        )
    }

    private fun nonEmptyAnalytics(): AnalyticsSnapshot {
        return AnalyticsSnapshot(
            spendingByCategory = listOf(
                CategorySpending("FOOD", BigDecimal("1000.00"), 3)
            ),
            monthlyDelta = listOf(
                CategoryDelta("FOOD", BigDecimal("1000.00"), BigDecimal("900.00"), BigDecimal("100.00"), 11.11)
            ),
            topMerchants = listOf(
                MerchantStat("COFFEE", BigDecimal("500.00"), 5)
            ),
            spikes = listOf(
                SpikeInfo("FOOD", LocalDate.now(), BigDecimal("100.00"), BigDecimal("300.00"))
            )
        )
    }
}
