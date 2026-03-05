package com.finsense.classifier.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    private final TextNormalizer textNormalizer = new TextNormalizer();

    @Test
    void normalizesCasePunctuationAndWhitespace() {
        String normalized = textNormalizer.normalize("  STARBUCKS.Coffee!!!   SHOP ");
        assertThat(normalized).isEqualTo("starbucks coffee shop");
    }

    @Test
    void returnsEmptyWhenInputIsNull() {
        assertThat(textNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void returnsEmptyWhenInputIsBlank() {
        assertThat(textNormalizer.normalize("   ")).isEmpty();
    }
}
