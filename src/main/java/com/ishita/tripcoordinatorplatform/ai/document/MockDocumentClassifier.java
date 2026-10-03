package com.ishita.tripcoordinatorplatform.ai.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(
        name = "app.ai.document-classification.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockDocumentClassifier implements DocumentClassifier {

    @Override
    public DocumentClassification classify(DocumentAiInput document) {

        if (document == null
                || document.content() == null
                || document.content().length == 0) {
            return DocumentClassification.UNSUPPORTED;
        }


        // The mock does not attempt to understand binary PDF/image contents.
        // Filename-based behavior only lets us exercise the processing pipeline
        // until a real document-capable AI provider is connected.
        String fileName = document.originalFileName().toLowerCase();

        if (fileName.contains("flight")) {
            return DocumentClassification.FLIGHT_CONFIRMATION;
        }

        return DocumentClassification.UNSUPPORTED;
    }
}
