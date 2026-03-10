package com.finsense.coach.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.finsense.coach.config.AppProperties
import com.finsense.coach.config.LlmProperties
import com.finsense.coach.logging.LLMLogger
import com.finsense.coach.util.CoachTools
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.test.util.ReflectionTestUtils
import java.util.*

@ExtendWith(MockitoExtension::class)
class LLMServiceTest {

    @Mock
    private lateinit var coachTools: CoachTools

    @Mock
    private lateinit var llmLogger: LLMLogger

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

    private lateinit var objectMapper: ObjectMapper
    private lateinit var llmService: LLMService

    @BeforeEach
    fun setUp() {
        objectMapper = spy(ObjectMapper())
        whenever(chatClientBuilderProvider.getIfAvailable()).thenReturn(chatClientBuilder)
        whenever(chatClientBuilder.build()).thenReturn(chatClient)
        whenever(chatClient.prompt()).thenReturn(requestSpec)
        whenever(requestSpec.system(any<String>())).thenReturn(requestSpec)
        whenever(requestSpec.user(any<String>())).thenReturn(requestSpec)
        whenever(requestSpec.tools(any())).thenReturn(requestSpec)
        whenever(requestSpec.call()).thenReturn(callSpec)

        llmService = LLMService(
            appProperties = AppProperties(llm = LlmProperties(timeoutSeconds = 5)),
            objectMapper = objectMapper,
            coachTools = coachTools,
            llmLogger = llmLogger,
            chatClientBuilderProvider = chatClientBuilderProvider
        )
        ReflectionTestUtils.setField(llmService, "openAiApiKey", "test-key")
        ReflectionTestUtils.setField(llmService, "openAiModel", "deepseek-configured")
    }

    @Test
    fun `returns parsed advice and extracts model tokens from chat metadata`() {
        whenever(callSpec.chatResponse()).thenReturn(
            chatResponse(
                text = """{"summary":"S","advice":"A"}""",
                model = "deepseek-used",
                usage = DefaultUsage(10, 5, 15, mapOf("prompt_tokens" to 10))
            )
        )

        val result = llmService.generateAdvice(UUID.randomUUID(), UUID.randomUUID(), 30, "дай совет")

        assertThat(result.summary).isEqualTo("S")
        assertThat(result.advice).isEqualTo("A")
        assertThat(result.usedModel).isEqualTo("deepseek-used")
        assertThat(result.totalTokens).isEqualTo(15)
        assertThat(result.rawText).isEqualTo("""{"summary":"S","advice":"A"}""")
        verify(llmLogger).log(any())
    }

    @Test
    fun `logs full chat response before parsing content`() {
        val response = chatResponse(
            text = """{"summary":"S","advice":"A"}""",
            model = "deepseek-used",
            usage = DefaultUsage(1, 1, 2, mapOf("ok" to true))
        )
        whenever(callSpec.chatResponse()).thenReturn(response)

        llmService.generateAdvice(UUID.randomUUID(), UUID.randomUUID(), 7, "дай совет")

        val order = inOrder(llmLogger, objectMapper)
        order.verify(llmLogger).log(any())
        order.verify(objectMapper).readTree(any<String>())

        verify(llmLogger).log(check { record ->
            assertThat(record.response).isEqualTo(response)
            assertThat(record.systemPrompt).contains("Верни строго один JSON-объект")
        })
    }

    @Test
    fun `missing metadata falls back to configured model and null tokens`() {
        whenever(callSpec.chatResponse()).thenReturn(
            chatResponse(
                text = """{"summary":"S","advice":"A"}""",
                model = "",
                usage = null
            )
        )

        val result = llmService.generateAdvice(UUID.randomUUID(), UUID.randomUUID(), 30, "дай совет")

        assertThat(result.usedModel).isEqualTo("deepseek-configured")
        assertThat(result.totalTokens).isNull()
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
