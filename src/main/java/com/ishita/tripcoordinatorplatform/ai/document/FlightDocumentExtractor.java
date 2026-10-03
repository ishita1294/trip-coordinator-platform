package com.ishita.tripcoordinatorplatform.ai.document;

public interface FlightDocumentExtractor {
    // Returns raw provider output so the backend can parse and validate
    // AI-generated data before treating it as trusted application data.
    String extract(DocumentAiInput document);
}
