package com.finsense.coach.repository

import com.finsense.coach.model.RecommendationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecommendationRepository : JpaRepository<RecommendationEntity, UUID>
