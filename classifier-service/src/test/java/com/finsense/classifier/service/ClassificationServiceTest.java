package com.finsense.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.finsense.classifier.config.ClassificationProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationServiceTest {

    @Test
    void selectsConfiguredStrategy() {
        ClassificationStrategy rule = mockStrategy("rule");
        ClassificationStrategy ml = mockStrategy("ml");
        ClassificationProperties properties = new ClassificationProperties();
        properties.setStrategy("ml");

        ClassificationService service = new ClassificationService(List.of(rule, ml), properties);

        assertThat(service).isNotNull();
    }

    @Test
    void failsWhenStrategyIsUnsupported() {
        ClassificationStrategy rule = mockStrategy("rule");
        ClassificationProperties properties = new ClassificationProperties();
        properties.setStrategy("ml");

        assertThatThrownBy(() -> new ClassificationService(List.of(rule), properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unsupported classification strategy");
    }

    private ClassificationStrategy mockStrategy(String id) {
        return new ClassificationStrategy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public com.finsense.classifier.model.ClassificationDecision classify(
                com.finsense.classifier.model.ClassificationInput input
            ) {
                throw new UnsupportedOperationException("Not used in unit test");
            }
        };
    }
}
