package com.ishita.tripcoordinatorplatform.ai.document;

public interface AttractionTourDocumentExtractor {
    AttractionTourExtractionResult extract(DocumentAiInput document);
    /** Explicit per-job read-only evaluation; no fallback to a different contract. */
    default String extractSourceBookingV1(DocumentAiInput document) {
        throw new UnsupportedOperationException("Native SourceBooking extraction is unavailable");
    }

    /** Staged v2 contract; production processing remains on extract() until consumers are integrated. */
    default SemanticTourExtraction extractSemantic(DocumentAiInput document) {
        throw new UnsupportedOperationException("Semantic extraction is not supported by this adapter");
    }
    /** Extracts the current semantic contract when the processing job explicitly selects it. */
    default SemanticTourExtractionV3 extractSemanticV3(DocumentAiInput document) {
        throw new UnsupportedOperationException(
                "The configured adapter does not support V3.4 extraction");
    }
    default TourSourceAssessment assessCorrections(DocumentAiInput document, String originalBookings,
            java.util.List<com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change> changes) {
        throw new UnsupportedOperationException("Source reinspection is unavailable");
    }
}
