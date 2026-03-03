package com.finsense.core.service

import com.finsense.core.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class RecommendationScheduler(
    private val userRepository: UserRepository,
    private val recommendationService: RecommendationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.scheduler.coach-cron}")
    fun scheduleRecommendations() {
        val userIds = userRepository.findAll().map { it.id }
        if (userIds.isEmpty()) {
            return
        }

        userIds.forEach { userId ->
            recommendationService.createRecommendationRequest(
                userId = userId,
                parameters = null,
                trigger = "SCHEDULED"
            )
        }
        log.info("Scheduled recommendation requests for {} users", userIds.size)
    }
}
