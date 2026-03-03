package com.finsense.core.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "core")
class UserEntity(
    @Id
    @Column(nullable = false)
    var id: UUID,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
