package com.finsense.core.e2e

import com.finsense.core.dto.client.ClassifierResponse
import com.finsense.core.dto.kafka.LlmClassifierResponseEvent
import com.finsense.core.dto.kafka.RawTransactionEvent
import com.finsense.core.model.TransactionStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

class KafkaTransactionFlowE2ETest : BaseE2ETest() {

    @Test
    fun `raw-transactions with high ML confidence persists classified and does not publish llm request`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")


        doReturn(ClassifierResponse(category = "FOOD_AND_DRINKS", confidence = 0.97))
            .whenever(classifierClient)
            .classify(any())

        val event = RawTransactionEvent(
            transactionId = transactionId,
            userId = userId,
            amount = BigDecimal("299.99"),
            description = "Coffee and snacks",
            merchantName = "Starbucks",
            mccCode = "5812",
            timestamp = Instant.parse("2026-02-10T10:00:00Z")
        )

        kafkaTemplate.send("raw-transactions", transactionId.toString(), objectMapper.writeValueAsString(event)).get()

        await.atMost(10, TimeUnit.SECONDS).untilAsserted {
            val row = dbSeedHelper.transactionRow(transactionId)
            assertThat(row).isNotNull
            assertThat(row!!["status"]).isEqualTo(TransactionStatus.CLASSIFIED.name)
            assertThat(row["classifier_source"]).isEqualTo("ML")
            assertThat(row["category"]).isEqualTo("FOOD_AND_DRINKS")
            assertThat((row["classifier_confidence"] as Number).toDouble()).isCloseTo(0.97, within(0.0001))
        }

        val llmMessage = kafkaProbeHelper.consumeSingle(
            topic = "llm-classifier-requests",
            timeout = Duration.ofSeconds(3)
        ) { it.value().contains(transactionId.toString()) }

        assertThat(llmMessage).isNull()
    }

    @Test
    fun `raw-transactions with low ML confidence publishes llm fallback request`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val historyTxId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")
        dbSeedHelper.insertTransaction(
            transactionId = historyTxId,
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("100.00"),
            description = "Historical",
            merchantName = "Shop",
            mccCode = "5311",
            transactionDate = Instant.parse("2026-02-09T10:00:00Z"),
            status = "CLASSIFIED",
            category = "SHOPPING",
            classifierSource = "ML",
            classifierConfidence = 0.95,
            classifiedAt = Instant.parse("2026-02-09T10:00:01Z")
        )

        doReturn(ClassifierResponse(category = "OTHER", confidence = 0.55))
            .whenever(classifierClient)
            .classify(any())


        val event = RawTransactionEvent(
            transactionId = transactionId,
            userId = userId,
            amount = BigDecimal("42.00"),
            description = "Something unclear",
            merchantName = "Unknown merchant",
            mccCode = null,
            timestamp = Instant.parse("2026-02-10T11:00:00Z")
        )
        kafkaTemplate.send("raw-transactions", transactionId.toString(), objectMapper.writeValueAsString(event)).get()

        await.atMost(10, TimeUnit.SECONDS).untilAsserted {
            val row = dbSeedHelper.transactionRow(transactionId)
            assertThat(row).isNotNull
            assertThat(row!!["status"]).isEqualTo(TransactionStatus.LLM_CLASSIFYING.name)
            assertThat(row["classifier_source"]).isEqualTo("ML")
            assertThat((row["classifier_confidence"] as Number).toDouble()).isCloseTo(0.55, within(0.0001))
        }

        val llmRequestRecord = kafkaProbeHelper.consumeSingle(
            topic = "llm-classifier-requests",
            timeout = Duration.ofSeconds(10)
        ) { it.value().contains(transactionId.toString()) }

        assertThat(llmRequestRecord).isNotNull
        val llmPayload = objectMapper.readTree(llmRequestRecord!!.value())
        assertThat(llmPayload["transactionId"].asText()).isEqualTo(transactionId.toString())
        assertThat(llmPayload["requestId"].asText()).isNotBlank()
        assertThat(llmPayload["predictedCategory"].asText()).isEqualTo("OTHER")
        assertThat(llmPayload["history"].isArray).isTrue()
        assertThat(llmPayload["history"].size()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `llm-classifier-responses updates pending transaction to classified`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")
        dbSeedHelper.insertTransaction(
            transactionId = transactionId,
            accountId = accountId,
            userId = userId,
            amount = BigDecimal("10.00"),
            description = "Pending llm",
            merchantName = "Unknown",
            mccCode = null,
            transactionDate = Instant.parse("2026-02-10T12:00:00Z"),
            status = "LLM_CLASSIFYING",
            category = "OTHER",
            classifierSource = "ML",
            classifierConfidence = 0.4,
            classifiedAt = null
        )

        val responseEvent = LlmClassifierResponseEvent(
            requestId = UUID.randomUUID(),
            transactionId = transactionId,
            category = "TRANSPORT",
            confidence = 0.93,
            processedAt = Instant.parse("2026-02-10T12:00:05Z")
        )
        kafkaTemplate.send(
            "llm-classifier-responses",
            transactionId.toString(),
            objectMapper.writeValueAsString(responseEvent)
        ).get()

        await.atMost(10, TimeUnit.SECONDS).untilAsserted {
            val row = dbSeedHelper.transactionRow(transactionId)
            assertThat(row).isNotNull
            assertThat(row!!["status"]).isEqualTo(TransactionStatus.CLASSIFIED.name)
            assertThat(row["classifier_source"]).isEqualTo("LLM")
            assertThat(row["category"]).isEqualTo("TRANSPORT")
            assertThat((row["classifier_confidence"] as Number).toDouble()).isCloseTo(0.93, within(0.0001))
            assertThat(row["classified_at"]).isNotNull()
        }
    }

    @Test
    fun `duplicate raw transaction events create single row`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        dbSeedHelper.insertUser(userId)
        dbSeedHelper.insertAccount(accountId, userId, number = "ACC-${UUID.randomUUID()}")

        doReturn(ClassifierResponse(category = "SHOPPING", confidence = 0.96))
            .whenever(classifierClient)
            .classify(any())

        val event = RawTransactionEvent(
            transactionId = transactionId,
            userId = userId,
            amount = BigDecimal("888.00"),
            description = "Order",
            merchantName = "Amazon",
            mccCode = "5311",
            timestamp = Instant.parse("2026-02-10T13:00:00Z")
        )
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send("raw-transactions", transactionId.toString(), payload).get()
        kafkaTemplate.send("raw-transactions", transactionId.toString(), payload).get()

        await.atMost(10, TimeUnit.SECONDS).untilAsserted {
            val count = dbSeedHelper.countTransactionsById(transactionId)
            assertThat(count).isEqualTo(1)
            val row = dbSeedHelper.transactionRow(transactionId)
            assertThat(row).isNotNull
            assertThat(row!!["status"]).isEqualTo(TransactionStatus.CLASSIFIED.name)
        }
    }
}
