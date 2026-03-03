package com.finsense.core.infrastructure.client

import com.finsense.core.config.AppProperties
import com.finsense.core.dto.client.ClassifierRequest
import com.finsense.core.dto.client.ClassifierResponse
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class ClassifierClient(
    private val restTemplate: RestTemplate,
    private val appProperties: AppProperties
) {
    fun classify(request: ClassifierRequest): ClassifierResponse {
        val url = "${appProperties.classifier.url}/api/classify"
        return restTemplate.postForObject(url, request, ClassifierResponse::class.java)
            ?: throw IllegalStateException("Classifier service returned empty response")
    }
}
