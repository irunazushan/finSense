package com.finsense.classifier.api;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finsense.classifier.ClassifierServiceApplication;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = ClassifierServiceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.classification.rules-file=classpath:test-classifier-rules.yaml",
    "app.classification.strategy=ml"
})
@DisabledOnOs(OS.WINDOWS)
class MlClassifyControllerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws URISyntaxException {
        Path modelDir = Path.of(
            Objects.requireNonNull(
                MlClassifyControllerApiTest.class.getResource("/model/transaction-classifier.onnx")
            ).toURI()
        ).getParent();
        registry.add("app.classification.model.dir", modelDir::toString);
    }

    @Test
    void classifyReturnsMlPredictionForCoffee() throws Exception {
        String payload = """
            {
              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
              "amount": 350.00,
              "description": "coffee payment",
              "merchantName": "Starbucks Cafe",
              "mccCode": "5812"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value("550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(jsonPath("$.category").value("FOOD_AND_DRINKS"))
            .andExpect(jsonPath("$.confidence").value(closeTo(0.9961, 0.0001)))
            .andExpect(jsonPath("$.source").value("ML"));
    }

    @Test
    void classifyReturnsMlPredictionForTransport() throws Exception {
        String payload = """
            {
              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
              "amount": 900.00,
              "description": "taxi ride",
              "merchantName": "Yandex Taxi",
              "mccCode": "4121"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("TRANSPORT"))
            .andExpect(jsonPath("$.confidence").value(closeTo(0.9972, 0.0001)))
            .andExpect(jsonPath("$.source").value("ML"));
    }
}
