package com.finsense.core.repository

import com.finsense.core.model.AccountEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountRepository : JpaRepository<AccountEntity, UUID> {
    fun findFirstByUserIdOrderByCreatedAtAsc(userId: UUID): AccountEntity?
}
