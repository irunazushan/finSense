package com.finsense.coach.dto.kafka

import java.time.Instant
import java.util.UUID

data class CoachResponseEvent(
    val requestId: UUID,
    val userId: UUID,
    val summary: String,
    val advice: String,
    val completedAt: Instant,
    val status: String,
    val error: String? = null
)
