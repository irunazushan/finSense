package com.finsense.core.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transactions", schema = "core")
class TransactionEntity(
    @Id
    @Column(nullable = false)
    var id: UUID,

    @Column(name = "account_id", nullable = false)
    var accountId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,

    @Column
    var description: String?,

    @Column(name = "merchant_name")
    var merchantName: String?,

    @Column(name = "mcc_code")
    var mccCode: String?,

    @Column(name = "transaction_date", nullable = false)
    var transactionDate: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TransactionStatus = TransactionStatus.NEW,

    @Column
    var category: String?,

    @Column(name = "classifier_source")
    var classifierSource: String?,

    @Column(name = "classifier_confidence")
    var classifierConfidence: Double?,

    @Column(name = "classified_at")
    var classifiedAt: Instant?
)
