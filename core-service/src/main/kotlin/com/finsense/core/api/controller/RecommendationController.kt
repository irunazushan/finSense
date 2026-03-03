package com.finsense.core.api.controller

import com.finsense.core.dto.api.CreateRecommendationRequest
import com.finsense.core.dto.api.RecommendationAcceptedResponse
import com.finsense.core.dto.api.RecommendationResponse
import com.finsense.core.service.RecommendationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class RecommendationController(
    private val recommendationService: RecommendationService
) {
    @PostMapping("/users/{userId}/recommendations")
    fun createRecommendation(
        @PathVariable userId: UUID,
        @RequestBody(required = false) request: CreateRecommendationRequest?
    ): ResponseEntity<RecommendationAcceptedResponse> {
        val response = recommendationService.createRecommendationRequest(
            userId = userId,
            parameters = request?.parameters
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }

    @GetMapping("/users/{userId}/recommendations")
    fun listRecommendations(
        @PathVariable userId: UUID,
        @RequestParam(defaultValue = "20") limit: Int
    ): List<RecommendationResponse> {
        return recommendationService.listCompletedRecommendations(userId, limit)
    }

    @GetMapping("/recommendations/{requestId}")
    fun getRecommendation(
        @PathVariable requestId: UUID
    ): ResponseEntity<RecommendationResponse> {
        val recommendation = recommendationService.getRecommendation(requestId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(recommendation)
    }
}
