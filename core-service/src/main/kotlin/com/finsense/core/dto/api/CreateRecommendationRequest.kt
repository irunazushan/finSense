package com.finsense.core.dto.api

import com.fasterxml.jackson.databind.JsonNode

data class CreateRecommendationRequest(
    val parameters: JsonNode? = null
)
