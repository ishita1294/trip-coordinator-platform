package com.ishita.tripcoordinatorplatform.ai.document;

import org.springframework.stereotype.Component;

@Component
public class DocumentClassificationParser {

    public DocumentClassification parse(String rawClassification) {

        if (rawClassification == null || rawClassification.isBlank()) {
            throw new IllegalArgumentException(
                    "Document classification response is empty"
            );
        }

        String value = rawClassification.trim();

        try {
            return DocumentClassification.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported document classification response: " + value,
                    exception
            );
        }
    }
}
