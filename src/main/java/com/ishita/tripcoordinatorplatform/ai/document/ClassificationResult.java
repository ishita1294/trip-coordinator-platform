package com.ishita.tripcoordinatorplatform.ai.document;

/** A document category plus, for tour/attraction documents when requested, the AI-derived booking inventory. */
public record ClassificationResult(DocumentClassification classification, BookingInventory inventory) {
    public ClassificationResult {
        inventory = inventory == null ? BookingInventory.empty() : inventory;
    }

    public static ClassificationResult withoutInventory(DocumentClassification classification) {
        return new ClassificationResult(classification, BookingInventory.empty());
    }
}
