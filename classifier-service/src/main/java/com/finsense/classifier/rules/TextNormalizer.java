package com.finsense.classifier.rules;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {

    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    }
}
