package com.finsense.coach.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.e2e.utils.DbSeedHelper
import com.finsense.coach.e2e.utils.KafkaProbeHelper
import com.finsense.coach.llm.LLMService
import com.finsense.coach.llm.LlmAdviceResult
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyInt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("e2e")
abstract class BaseE2ETest {

    @Autowired
    protected lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var dbSeedHelper: DbSeedHelper

    @Autowired
    protected lateinit var kafkaProbeHelper: KafkaProbeHelper

    @MockitoBean
    protected lateinit var llmService: LLMService

    @BeforeEach
    fun cleanDb() {
        dbSeedHelper.truncateAll()
        doReturn(true).`when`(llmService).isConfigured()
        doReturn(
            LlmAdviceResult(
                summary = "mock-summary",
                advice = "mock-advice",
                rawText = """{"summary":"mock-summary","advice":"mock-advice"}""",
                tokens = 12,
                latencyMs = 10
            )
        ).`when`(llmService).generateAdvice(any(), any(), anyInt(), any(), any())
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
        private val kafka: ConfluentKafkaContainer = ConfluentKafkaContainer(DockerImageName.parse(KAFKA_IMAGE))

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
            registry.add("app.kafka.topics.coach-requests") { "coach-requests" }
            registry.add("app.kafka.topics.coach-responses") { "coach-responses" }
            registry.add("LLM_API_KEY") { "test-key-for-disabled-llm" }
        }

        @JvmStatic
        private fun createTopics() {
            val props = mapOf("bootstrap.servers" to kafka.bootstrapServers)
            AdminClient.create(props).use { admin ->
                val requiredTopics = setOf("coach-requests", "coach-responses")
                val existing = admin.listTopics().names().get(10, TimeUnit.SECONDS)
                val toCreate = requiredTopics.minus(existing).map {
                    NewTopic(it, 1, 1.toShort())
                }
                if (toCreate.isNotEmpty()) {
                    admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}
