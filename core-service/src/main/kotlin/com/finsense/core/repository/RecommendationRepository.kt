package com.finsense.core.repository

import com.finsense.core.model.RecommendationEntity
import com.finsense.core.model.RecommendationStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecommendationRepository : JpaRepository<RecommendationEntity, UUID> {
    fun findByUserIdAndStatusOrderByCreatedAtDesc(
        userId: UUID,
        status: RecommendationStatus,
        pageable: Pageable
    ): List<RecommendationEntity>
}
