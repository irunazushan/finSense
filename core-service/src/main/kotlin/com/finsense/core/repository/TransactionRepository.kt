package com.finsense.core.repository

import com.finsense.core.model.TransactionEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface TransactionRepository : JpaRepository<TransactionEntity, UUID>, JpaSpecificationExecutor<TransactionEntity> {
    fun findByUserIdAndStatusOrderByTransactionDateDesc(
        userId: UUID,
        status: com.finsense.core.model.TransactionStatus,
        pageable: Pageable
    ): List<TransactionEntity>
}
