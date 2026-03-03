package com.finsense.core.dto.api

import com.finsense.core.model.RecommendationStatus
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class RecommendationAcceptedResponse(
    val requestId: UUID,
    val status: RecommendationStatus
)

data class RecommendationResponse(
    val requestId: UUID,
    val userId: UUID,
    val status: RecommendationStatus,
    val createdAt: Instant,
    val completedAt: Instant?,
    val adviceData: JsonNode?,
    val requestParams: JsonNode?,
    val error: String?
)
