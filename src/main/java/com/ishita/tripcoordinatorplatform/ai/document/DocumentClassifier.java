package com.ishita.tripcoordinatorplatform.ai.document;

public interface DocumentClassifier {

    DocumentClassification classify(DocumentAiInput document);

    /** Adapters that cannot list bookings return the plain classification with an empty inventory. */
    default ClassificationResult classifyWithInventory(DocumentAiInput document) {
        return ClassificationResult.withoutInventory(classify(document));
    }
}
