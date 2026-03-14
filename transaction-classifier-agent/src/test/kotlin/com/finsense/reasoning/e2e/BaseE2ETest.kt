package com.finsense.reasoning.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.llm.LlmClassificationResult
import com.finsense.reasoning.e2e.utils.KafkaProbeHelper
import com.finsense.reasoning.service.LLMService
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
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
    protected lateinit var kafkaProbeHelper: KafkaProbeHelper

    @MockitoBean
    protected lateinit var llmService: LLMService

    @BeforeEach
    fun setUpMocks() {
        doReturn(true).`when`(llmService).isConfigured()
        doReturn(
            LlmClassificationResult(
                category = "TRANSPORT",
                confidence = 0.93,
                reasoning = "mocked",
                rawText = """{"category":"TRANSPORT","confidence":0.93}""",
                usedModel = "deepseek-chat",
                totalTokens = 7,
                latencyMs = 15
            )
        ).`when`(llmService).classify(any<LlmClassifierRequestEvent>())
    }

    companion object {
        private const val KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.1"

        @JvmStatic
        private val kafka: ConfluentKafkaContainer = ConfluentKafkaContainer(DockerImageName.parse(KAFKA_IMAGE))

        @JvmStatic
        private val logDir = Files.createTempDirectory("reasoning-e2e-logs")

        init {
            if (!kafka.isRunning) {
                kafka.start()
            }
            createTopics()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("KAFKA_BOOTSTRAP_SERVERS", kafka::getBootstrapServers)
            registry.add("app.kafka.topics.llm-classifier-requests") { "llm-classifier-requests" }
            registry.add("app.kafka.topics.llm-classifier-responses") { "llm-classifier-responses" }
            registry.add("app.logging.llm-logs-dir") { logDir.toString() }
            registry.add("spring.ai.openai.api-key") { "test-key-for-disabled-llm" }
        }

        @JvmStatic
        private fun createTopics() {
            val props = mapOf("bootstrap.servers" to kafka.bootstrapServers)
            AdminClient.create(props).use { admin ->
                val requiredTopics = setOf("llm-classifier-requests", "llm-classifier-responses")
                val existing = admin.listTopics().names().get(10, TimeUnit.SECONDS)
                val toCreate = requiredTopics.minus(existing).map { NewTopic(it, 1, 1.toShort()) }
                if (toCreate.isNotEmpty()) {
                    admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS)
                }
            }
        }
    }
}
