package com.finsense.reasoning.logging

import com.finsense.reasoning.config.AppProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths

@Component
class LlmLogDirectoryInitializer(
    private val appProperties: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun createLogDirectory() {
        val path = Paths.get(appProperties.logging.llmLogsDir)
        runCatching { Files.createDirectories(path) }
            .onFailure { ex ->
                log.warn("Failed to create LLM log directory {} at startup: {}", path.toAbsolutePath(), ex.message)
            }
    }
}
