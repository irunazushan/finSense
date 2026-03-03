package com.finsense.core.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.core.config.AppProperties
import com.finsense.core.dto.api.RecommendationAcceptedResponse
import com.finsense.core.dto.api.RecommendationResponse
import com.finsense.core.dto.kafka.CoachRequestEvent
import com.finsense.core.infrastructure.kafka.KafkaEventPublisher
import com.finsense.core.model.RecommendationEntity
import com.finsense.core.model.RecommendationStatus
import com.finsense.core.repository.RecommendationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class RecommendationService(
    private val recommendationRepository: RecommendationRepository,
    private val kafkaEventPublisher: KafkaEventPublisher,
    private val userBootstrapService: UserBootstrapService,
    private val objectMapper: ObjectMapper,
    private val appProperties: AppProperties
) {
    @Transactional
    fun createRecommendationRequest(
        userId: UUID,
        parameters: JsonNode?,
        trigger: String = "MANUAL"
    ): RecommendationAcceptedResponse {
        userBootstrapService.ensureUserAndAccount(userId)

        val requestId = UUID.randomUUID()
        val entity = recommendationRepository.save(
            RecommendationEntity(
                id = requestId,
                userId = userId,
                status = RecommendationStatus.PENDING,
                requestParams = parameters?.let { objectMapper.writeValueAsString(it) }
            )
        )

        kafkaEventPublisher.publish(
            appProperties.kafka.topics.coachRequests,
            requestId.toString(),
            CoachRequestEvent(
                requestId = requestId,
                userId = userId,
                trigger = trigger,
                requestedAt = Instant.now(),
                parameters = parameters
            )
        )

        return RecommendationAcceptedResponse(
            requestId = entity.id,
            status = entity.status
        )
    }

    @Transactional(readOnly = true)
    fun getRecommendation(requestId: UUID): RecommendationResponse? {
        return recommendationRepository.findById(requestId).orElse(null)?.toDto()
    }

    @Transactional(readOnly = true)
    fun listCompletedRecommendations(userId: UUID, limit: Int): List<RecommendationResponse> {
        return recommendationRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
            userId,
            RecommendationStatus.COMPLETED,
            PageRequest.of(0, limit.coerceIn(1, 100))
        ).map { it.toDto() }
    }

    private fun RecommendationEntity.toDto(): RecommendationResponse {
        return RecommendationResponse(
            requestId = id,
            userId = userId,
            status = status,
            createdAt = createdAt,
            completedAt = completedAt,
            adviceData = adviceData?.let { objectMapper.readTree(it) },
            requestParams = requestParams?.let { objectMapper.readTree(it) },
            error = error
        )
    }
}
