package com.finsense.coach.dto.kafka

import java.time.Instant
import java.util.UUID

data class CoachRequestEvent(
    val requestId: UUID,
    val userId: UUID,
    val trigger: String,
    val requestedAt: Instant,
    val parameters: CoachRequestParameters
)

data class CoachRequestParameters(
    val periodDays: Int,
    val message: String
)
