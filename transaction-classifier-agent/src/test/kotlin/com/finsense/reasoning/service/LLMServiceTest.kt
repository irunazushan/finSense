package com.finsense.reasoning.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.reasoning.config.AppProperties
import com.finsense.reasoning.config.LlmProperties
import com.finsense.reasoning.dto.kafka.LlmClassifierRequestEvent
import com.finsense.reasoning.dto.kafka.TransactionContext
import com.finsense.reasoning.logging.LLMLogger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class LLMServiceTest {

    @Mock
    private lateinit var llmLogger: LLMLogger

    @Mock
    private lateinit var llmPromptTemplates: LlmPromptTemplates

    @Mock
    private lateinit var chatClientBuilderProvider: ObjectProvider<ChatClient.Builder>

    @Mock
    private lateinit var chatClientBuilder: ChatClient.Builder

    @Mock
    private lateinit var chatClient: ChatClient

    @Mock
    private lateinit var requestSpec: ChatClient.ChatClientRequestSpec

    @Mock
    private lateinit var callSpec: ChatClient.CallResponseSpec

    private lateinit var llmService: LLMService

    @BeforeEach
    fun setUp() {
        whenever(chatClientBuilderProvider.getIfAvailable()).thenReturn(chatClientBuilder)
        whenever(chatClientBuilder.build()).thenReturn(chatClient)
        whenever(chatClient.prompt()).thenReturn(requestSpec)
        whenever(requestSpec.system(any<String>())).thenReturn(requestSpec)
        whenever(requestSpec.user(any<String>())).thenReturn(requestSpec)
        whenever(requestSpec.call()).thenReturn(callSpec)
        whenever(llmPromptTemplates.systemPrompt()).thenReturn("system prompt")
        whenever(llmPromptTemplates.renderUserPrompt(any())).thenReturn("rendered user prompt")

        llmService = LLMService(
            appProperties = AppProperties(llm = LlmProperties(timeoutSeconds = 5)),
            objectMapper = ObjectMapper(),
            llmPromptTemplates = llmPromptTemplates,
            llmLogger = llmLogger,
            chatClientBuilderProvider = chatClientBuilderProvider
        )
        ReflectionTestUtils.setField(llmService, "openAiApiKey", "test-key")
        ReflectionTestUtils.setField(llmService, "openAiModel", "deepseek-configured")
    }

    @Test
    fun `returns parsed classification and metadata`() {
        whenever(callSpec.chatResponse()).thenReturn(
            chatResponse(
                text = """{"category":"transport","confidence":0.91,"reasoning":"merchant and history match"}""",
                model = "deepseek-used",
                usage = DefaultUsage(10, 5, 15, mapOf("prompt_tokens" to 10))
            )
        )

        val result = llmService.classify(sampleEvent())

        assertThat(result.category).isEqualTo("TRANSPORT")
        assertThat(result.confidence).isEqualTo(0.91)
        assertThat(result.reasoning).isEqualTo("merchant and history match")
        assertThat(result.usedModel).isEqualTo("deepseek-used")
        assertThat(result.totalTokens).isEqualTo(15)
        verify(llmLogger).log(any())
    }

    @Test
    fun `logs failure details for invalid category`() {
        val response = chatResponse(
            text = """{"category":"BILLS","confidence":0.4,"reasoning":"bad category"}""",
            model = "deepseek-used",
            usage = DefaultUsage(1, 1, 2, emptyMap<String, Any>())
        )
        whenever(callSpec.chatResponse()).thenReturn(response)

        val thrown = catchThrowable { llmService.classify(sampleEvent()) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        verify(llmLogger).log(check {
            assertThat(it.rawResponse).isEqualTo(response)
            assertThat(it.success).isFalse()
            assertThat(it.error).contains("unsupported category")
        })
    }

    @Test
    fun `accepts fenced json response`() {
        whenever(callSpec.chatResponse()).thenReturn(
            chatResponse(
                text = "```json\n{\"category\":\"OTHER\",\"confidence\":0.33,\"reasoning\":\"unclear\"}\n```",
                model = "deepseek-used",
                usage = null
            )
        )

        val result = llmService.classify(sampleEvent())

        assertThat(result.category).isEqualTo("OTHER")
        assertThat(result.confidence).isEqualTo(0.33)
    }

    private fun sampleEvent(): LlmClassifierRequestEvent {
        return LlmClassifierRequestEvent(
            requestId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-02-10T10:15:30Z"),
            transaction = TransactionContext(
                userId = UUID.randomUUID(),
                amount = BigDecimal("100.00"),
                description = "Coffee",
                merchantName = "Starbucks",
                mccCode = "5812",
                transactionDate = Instant.parse("2026-02-10T10:10:00Z")
            ),
            confidence = 0.62,
            predictedCategory = "OTHER",
            history = emptyList()
        )
    }

    private fun chatResponse(
        text: String,
        model: String,
        usage: DefaultUsage?
    ): ChatResponse {
        val metadataBuilder = ChatResponseMetadata.builder().model(model)
        if (usage != null) {
            metadataBuilder.usage(usage)
        }
        return ChatResponse(
            listOf(Generation(AssistantMessage(text))),
            metadataBuilder.build()
        )
    }
}
