package com.finsense.classifier.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finsense.classifier.model.TransactionCategory;
import com.finsense.classifier.rules.RuleSet;
import com.finsense.classifier.rules.TextNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class RulesLoaderTest {

    @Test
    void loadsValidRulesFile() throws IOException {
        Path file = writeRules("""
            mcc:
              5812: FOOD_AND_DRINKS
            keywords:
              - category: TRANSPORT
                words: [taxi, metro]
            confidence:
              mcc_base_confirmed: 0.95
              mcc_base_unconfirmed: 0.75
              keyword_base: 0.85
              boost_per_match: 0.05
              contradiction_penalty: 0.07
              mcc_min: 0.55
              max: 0.99
            """);

        RulesLoader loader = new RulesLoader(propertiesFor(file), new DefaultResourceLoader(), new TextNormalizer());
        RuleSet ruleSet = loader.getRuleSet();

        assertThat(ruleSet.mccRules()).containsEntry("5812", TransactionCategory.FOOD_AND_DRINKS);
        assertThat(ruleSet.keywordPriority()).containsExactly(TransactionCategory.TRANSPORT);
    }

    @Test
    void failsOnUnsupportedCategory() throws IOException {
        Path file = writeRules("""
            mcc:
              5812: UNKNOWN_CATEGORY
            confidence:
              mcc_base_confirmed: 0.95
              mcc_base_unconfirmed: 0.75
              keyword_base: 0.85
              boost_per_match: 0.05
              contradiction_penalty: 0.07
              mcc_min: 0.55
              max: 0.99
            """);

        assertThatThrownBy(() -> new RulesLoader(propertiesFor(file), new DefaultResourceLoader(), new TextNormalizer()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unsupported category");
    }

    @Test
    void failsOnInvalidConfidenceBounds() throws IOException {
        Path file = writeRules("""
            confidence:
              mcc_base_confirmed: 1.1
              mcc_base_unconfirmed: 0.75
              keyword_base: 0.85
              boost_per_match: 0.05
              contradiction_penalty: 0.07
              mcc_min: 0.55
              max: 0.99
            """);

        assertThatThrownBy(() -> new RulesLoader(propertiesFor(file), new DefaultResourceLoader(), new TextNormalizer()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("confidence.mcc_base_confirmed");
    }

    private Path writeRules(String content) throws IOException {
        Path file = Files.createTempFile("classifier-rules", ".yaml");
        Files.writeString(file, content);
        file.toFile().deleteOnExit();
        return file;
    }

    private ClassificationProperties propertiesFor(Path rulesFile) {
        ClassificationProperties properties = new ClassificationProperties();
        properties.setRulesFile("file:" + rulesFile.toAbsolutePath());
        properties.setStrategy("rule");
        return properties;
    }
}
