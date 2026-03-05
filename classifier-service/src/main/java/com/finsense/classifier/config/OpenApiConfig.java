package com.finsense.classifier.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI classifierOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Classifier Service API")
                .version("v1")
                .description("Rule-based classification API for bank transactions"));
    }
}
