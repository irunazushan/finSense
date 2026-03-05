package com.finsense.core.dto.api

data class CreateRecommendationRequest(
    val parameters: RecommendationParametersDto? = null
)

data class RecommendationParametersDto(
    val periodDays: Int? = null,
    val message: String? = null
)
