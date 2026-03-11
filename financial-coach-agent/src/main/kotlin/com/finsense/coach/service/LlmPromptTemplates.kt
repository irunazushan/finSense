package com.finsense.coach.service

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

@Component
class LlmPromptTemplates(
    @Value("classpath:prompts/coach-system-v1.txt")
    private val systemTemplateResource: Resource,
    @Value("classpath:prompts/coach-user-v1.txt")
    private val userTemplateResource: Resource
) {
    companion object {
        const val SYSTEM_TEMPLATE_ID = "coach-system-v1"
        const val USER_TEMPLATE_ID = "coach-user-v1"
    }

    private lateinit var systemTemplate: String
    private lateinit var userTemplate: String

    @PostConstruct
    fun loadTemplates() {
        systemTemplate = readTemplate(systemTemplateResource, SYSTEM_TEMPLATE_ID)
        userTemplate = readTemplate(userTemplateResource, USER_TEMPLATE_ID)
    }

    fun systemPrompt(): String = systemTemplate

    fun renderUserPrompt(
        requestId: UUID,
        userId: UUID,
        periodDays: Int,
        userMessage: String
    ): String {
        return userTemplate
            .replace("{{requestId}}", requestId.toString())
            .replace("{{userId}}", userId.toString())
            .replace("{{periodDays}}", periodDays.toString())
            .replace("{{userMessage}}", userMessage)
            .trim()
    }

    private fun readTemplate(resource: Resource, templateId: String): String {
        val text = try {
            resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (ex: Exception) {
            throw IllegalStateException("Failed to load prompt template: $templateId", ex)
        }

        if (text.isBlank()) {
            throw IllegalStateException("Prompt template is empty: $templateId")
        }

        return text.trim()
    }
}

