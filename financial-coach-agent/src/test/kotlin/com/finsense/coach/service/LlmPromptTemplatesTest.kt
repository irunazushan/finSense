package com.finsense.coach.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.util.UUID

class LlmPromptTemplatesTest {

    @Test
    fun `loads templates and renders placeholders`() {
        val templates = LlmPromptTemplates(
            systemTemplateResource = ClassPathResource("prompts/coach-system-v1.txt"),
            userTemplateResource = ClassPathResource("prompts/coach-user-v1.txt")
        )
        templates.loadTemplates()

        val requestId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val rendered = templates.renderUserPrompt(
            requestId = requestId,
            userId = userId,
            periodDays = 30,
            userMessage = "Дай совет"
        )

        assertThat(templates.systemPrompt()).contains("Ты финансовый коуч")
        assertThat(rendered).contains(requestId.toString())
        assertThat(rendered).contains(userId.toString())
        assertThat(rendered).contains("periodDays: 30")
        assertThat(rendered).contains("Дай совет")
    }

    @Test
    fun `missing template fails fast with clear error`() {
        val templates = LlmPromptTemplates(
            systemTemplateResource = ClassPathResource("prompts/missing-system.txt"),
            userTemplateResource = ClassPathResource("prompts/coach-user-v1.txt")
        )

        assertThatThrownBy { templates.loadTemplates() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("coach-system-v1")
    }
}

