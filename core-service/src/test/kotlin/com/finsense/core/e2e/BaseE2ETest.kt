package com.finsense.core.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.core.e2e.utils.DbSeedHelper
import com.finsense.core.e2e.utils.KafkaProbeHelper
import com.finsense.core.infrastructure.client.ClassifierClient
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.awaitility.kotlin.await
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
abstract class BaseE2ETest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    protected lateinit var dbSeedHelper: DbSeedHelper

    @Autowired
    protected lateinit var kafkaProbeHelper: KafkaProbeHelper

    @MockitoBean
    protected lateinit var classifierClient: ClassifierClient

    @BeforeEach
    fun cleanDatabase() {
        dbSeedHelper.truncateAll()
    }

    protected fun awaitTransactionStatus(
        transactionId: UUID,
        expectedStatus: String,
        timeout: Duration = Duration.ofSeconds(10)
    ) {
        await.atMost(timeout.toSeconds(), TimeUnit.SECONDS).untilAsserted {
            val row = dbSeedHelper.transactionRow(transactionId)
            assertThat(row).isNotNull
            assertThat(row!!["status"]).isEqualTo(expectedStatus)
        }
    }

    companion object {
        private const val POSTGRES_IMAGE = "postgres:16-alpine"
        private const val KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.1"

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("finsense")
            .withUsername("finsense")
            .withPassword("finsense")

        @JvmStatic
        private val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))

        init {
            if (!postgres.isRunning) {
                postgres.start()
            }
            if (!kafka.isRunning) {
                kafka.start()
            }
            createTopics()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("DB_URL", postgres::getJdbcUrl)
            registry.add("DB_USER", postgres::getUsername)
            registry.add("DB_PASSWORD", postgres::getPassword)
            registry.add("KAFKA_BOOTSTRAP_SERVERS", kafka::getBootstrapServers)
            registry.add("spring.task.scheduling.enabled") { "false" }
            registry.add("app.scheduler.coach-cron") { "0 0 0 1 * ?" }
            registry.add("app.kafka.topics.raw-transactions") { "raw-transactions" }
            registry.add("app.kafka.topics.llm-classifier-responses") { "llm-classifier-responses" }
            registry.add("app.kafka.topics.llm-classifier-requests") { "llm-classifier-requests" }
            registry.add("app.kafka.topics.coach-requests") { "coach-requests" }
        }

        @JvmStatic
        private fun createTopics() {
            val props = mapOf("bootstrap.servers" to kafka.bootstrapServers)
            AdminClient.create(props).use { admin ->
                val requiredTopics = setOf(
                    "raw-transactions",
                    "llm-classifier-responses",
                    "llm-classifier-requests",
                    "coach-requests"
                )
                val existing = admin.listTopics().names().get(10, TimeUnit.SECONDS)
                val toCreate = requiredTopics.minus(existing).map {
                    NewTopic(it, 1, 1.toShort())
                }
                if (toCreate.isEmpty()) {
                    return
                }
                admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS)
            }
        }
    }
}
