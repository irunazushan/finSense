package com.finsense.core.service

import com.finsense.core.config.AppProperties
import com.finsense.core.dto.api.TransactionResponse
import com.finsense.core.dto.client.ClassifierRequest
import com.finsense.core.dto.kafka.HistoryTransaction
import com.finsense.core.dto.kafka.LlmClassifierRequestEvent
import com.finsense.core.dto.kafka.LlmClassifierResponseEvent
import com.finsense.core.dto.kafka.RawTransactionEvent
import com.finsense.core.dto.kafka.TransactionContext
import com.finsense.core.infrastructure.client.ClassifierClient
import com.finsense.core.infrastructure.kafka.KafkaEventPublisher
import com.finsense.core.model.TransactionEntity
import com.finsense.core.model.TransactionStatus
import com.finsense.core.repository.TransactionRepository
import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val userBootstrapService: UserBootstrapService,
    private val classifierClient: ClassifierClient,
    private val kafkaEventPublisher: KafkaEventPublisher,
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handleRawTransaction(event: RawTransactionEvent) {
        if (transactionRepository.existsById(event.transactionId)) {
            log.info("Skip duplicate transactionId={}", event.transactionId)
            return
        }

        val (_, account) = userBootstrapService.ensureUserAndAccount(event.userId)

        val transaction = transactionRepository.save(
            TransactionEntity(
                id = event.transactionId,
                accountId = account.id,
                userId = event.userId,
                amount = event.amount,
                description = event.description,
                merchantName = event.merchantName,
                mccCode = event.mccCode,
                transactionDate = event.timestamp,
                status = TransactionStatus.ML_CLASSIFYING,
                category = null,
                classifierSource = null,
                classifierConfidence = null,
                classifiedAt = null
            )
        )

        try {
            val classifierResponse = classifierClient.classify(
                ClassifierRequest(
                    transactionId = transaction.id,
                    amount = transaction.amount,
                    description = transaction.description,
                    merchantName = transaction.merchantName,
                    mccCode = transaction.mccCode
                )
            )

            if (classifierResponse.confidence >= appProperties.classifier.confidenceThreshold) {
                transaction.status = TransactionStatus.CLASSIFIED
                transaction.category = classifierResponse.category
                transaction.classifierSource = "ML"
                transaction.classifierConfidence = classifierResponse.confidence
                transaction.classifiedAt = Instant.now()
                transactionRepository.save(transaction)
                return
            }

            transaction.status = TransactionStatus.LLM_CLASSIFYING
            transaction.category = classifierResponse.category
            transaction.classifierSource = "ML"
            transaction.classifierConfidence = classifierResponse.confidence
            transactionRepository.save(transaction)

            val history = transactionRepository.findByUserIdAndStatusOrderByTransactionDateDesc(
                userId = transaction.userId,
                status = TransactionStatus.CLASSIFIED,
                pageable = PageRequest.of(0, appProperties.reasoning.historySize)
            ).map {
                HistoryTransaction(
                    transactionId = it.id,
                    amount = it.amount,
                    category = it.category,
                    merchantName = it.merchantName,
                    transactionDate = it.transactionDate
                )
            }

            val request = LlmClassifierRequestEvent(
                requestId = UUID.randomUUID(),
                transactionId = transaction.id,
                occurredAt = Instant.now(),
                transaction = TransactionContext(
                    userId = transaction.userId,
                    amount = transaction.amount,
                    description = transaction.description,
                    merchantName = transaction.merchantName,
                    mccCode = transaction.mccCode,
                    transactionDate = transaction.transactionDate
                ),
                confidence = classifierResponse.confidence,
                predictedCategory = classifierResponse.category,
                history = history
            )

            kafkaEventPublisher.publish(
                appProperties.kafka.topics.llmClassifierRequests,
                request.transactionId.toString(),
                request
            )
        } catch (ex: Exception) {
            transaction.status = TransactionStatus.FAILED
            transactionRepository.save(transaction)
            log.error("Failed to classify transactionId={}", transaction.id, ex)
        }
    }

    @Transactional
    fun handleLlmClassifierResponse(event: LlmClassifierResponseEvent) {
        val transaction = transactionRepository.findById(event.transactionId).orElse(null) ?: run {
            log.warn("Transaction not found for LLM response transactionId={}", event.transactionId)
            return
        }

        transaction.status = TransactionStatus.CLASSIFIED
        transaction.category = event.category
        transaction.classifierSource = "LLM"
        transaction.classifierConfidence = event.confidence
        transaction.classifiedAt = event.processedAt
        transactionRepository.save(transaction)
    }

    @Transactional(readOnly = true)
    fun findTransactions(
        userId: UUID,
        category: String?,
        status: TransactionStatus?,
        from: Instant?,
        to: Instant?,
        page: Int,
        size: Int
    ): List<TransactionResponse> {
        val specification = Specification<TransactionEntity> { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            predicates += cb.equal(root.get<UUID>("userId"), userId)
            if (!category.isNullOrBlank()) {
                predicates += cb.equal(root.get<String>("category"), category)
            }
            if (status != null) {
                predicates += cb.equal(root.get<TransactionStatus>("status"), status)
            }
            if (from != null) {
                predicates += cb.greaterThanOrEqualTo(root.get("transactionDate"), from)
            }
            if (to != null) {
                predicates += cb.lessThanOrEqualTo(root.get("transactionDate"), to)
            }
            cb.and(*predicates.toTypedArray())
        }

        return transactionRepository.findAll(
            specification,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDate"))
        ).content.map {
            TransactionResponse(
                transactionId = it.id,
                userId = it.userId,
                accountId = it.accountId,
                amount = it.amount,
                description = it.description,
                merchantName = it.merchantName,
                mccCode = it.mccCode,
                transactionDate = it.transactionDate,
                status = it.status,
                category = it.category,
                classifierSource = it.classifierSource,
                classifierConfidence = it.classifierConfidence,
                classifiedAt = it.classifiedAt
            )
        }
    }
}
