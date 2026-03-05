package com.finsense.core.dto.kafka

import com.finsense.core.model.RecommendationTrigger
import java.time.Instant
import java.util.UUID

data class CoachRequestEvent(
    val requestId: UUID,
    val userId: UUID,
    val trigger: RecommendationTrigger,
    val requestedAt: Instant,
    val parameters: CoachRequestParameters
)

data class CoachRequestParameters(
    val periodDays: Int,
    val message: String
)
