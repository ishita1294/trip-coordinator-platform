package com.ishita.tripcoordinatorplatform.ai.document;

public interface FlightDocumentExtractor {
    // Returns structured AI-generated data for backend validation
    // before treating it as trusted application data.
    FlightExtractionResult extract(DocumentAiInput document);
}
