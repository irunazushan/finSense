package com.finsense.core.service

import com.finsense.core.model.AccountEntity
import com.finsense.core.model.UserEntity
import com.finsense.core.repository.AccountRepository
import com.finsense.core.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserBootstrapService(
    private val userRepository: UserRepository,
    private val accountRepository: AccountRepository
) {
    @Transactional
    fun ensureUserAndAccount(userId: UUID): Pair<UserEntity, AccountEntity> {
        val user = userRepository.findById(userId).orElseGet {
            userRepository.save(
                UserEntity(
                    id = userId,
                    email = "$userId@finsense.local"
                )
            )
        }

        val account = accountRepository.findFirstByUserIdOrderByCreatedAtAsc(userId) ?: accountRepository.save(
            AccountEntity(
                id = UUID.randomUUID(),
                userId = userId,
                number = "ACC-${userId.toString().replace("-", "").take(20)}"
            )
        )

        return user to account
    }
}
