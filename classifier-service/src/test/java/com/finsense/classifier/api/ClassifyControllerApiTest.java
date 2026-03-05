package com.finsense.classifier.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finsense.classifier.ClassifierServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = ClassifierServiceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.classification.rules-file=classpath:test-classifier-rules.yaml",
    "app.classification.strategy=rule"
})
class ClassifyControllerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void classifyReturnsFullResponse() throws Exception {
        String payload = """
            {
              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
              "amount": 100.00,
              "description": "Coffee at Starbucks",
              "merchantName": "Starbucks",
              "mccCode": "5812"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value("550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(jsonPath("$.category").value("FOOD_AND_DRINKS"))
            .andExpect(jsonPath("$.confidence").value(0.99))
            .andExpect(jsonPath("$.source").value("RULE"));
    }

    @Test
    void classifyReturnsBadRequestWhenTransactionIdMissing() throws Exception {
        String payload = """
            {
              "amount": 100.00,
              "description": "Coffee"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void classifyReturnsBadRequestWhenAmountMissing() throws Exception {
        String payload = """
            {
              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
              "description": "Coffee"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void classifyReturnsUndefinedWhenNoMatch() throws Exception {
        String payload = """
            {
              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
              "amount": 42.50,
              "description": "Unmapped merchant",
              "merchantName": "Unknown",
              "mccCode": "9999"
            }
            """;

        mockMvc.perform(post("/api/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("UNDEFINED"))
            .andExpect(jsonPath("$.confidence").value(0.0))
            .andExpect(jsonPath("$.source").value("RULE"));
    }
}
