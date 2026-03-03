package com.finsense.core.dto.kafka

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class CoachRequestEvent(
    val requestId: UUID,
    val userId: UUID,
    val trigger: String,
    val requestedAt: Instant,
    val parameters: JsonNode? = null
)
