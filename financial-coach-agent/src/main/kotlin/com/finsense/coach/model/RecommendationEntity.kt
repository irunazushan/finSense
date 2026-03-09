package com.finsense.coach.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "recommendations", schema = "recommendations")
class RecommendationEntity(
    @Id
    @Column(nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RecommendationStatus = RecommendationStatus.PENDING,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "advice_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var adviceData: String? = null,

    @Column(name = "request_params", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var requestParams: String? = null,

    @Column
    var error: String? = null
)
