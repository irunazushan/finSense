package com.finsense.core.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "accounts", schema = "core")
class AccountEntity(
    @Id
    @Column(nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false, unique = true)
    var number: String,

    @Column(nullable = false)
    var type: String = "debit",

    @Column(nullable = false)
    var currency: String = "RUB",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
