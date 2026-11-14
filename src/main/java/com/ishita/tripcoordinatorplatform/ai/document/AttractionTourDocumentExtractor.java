package com.ishita.tripcoordinatorplatform.ai.document;

public interface AttractionTourDocumentExtractor {
    AttractionTourExtractionResult extract(DocumentAiInput document);
    /** Explicit per-job read-only evaluation; no fallback to a different contract. */
    default String extractSourceBooking(DocumentAiInput document) {
        throw new UnsupportedOperationException("Native SourceBooking extraction is unavailable");
    }

    /** Extracts the current semantic contract when the processing job explicitly selects it. */
    default TourReading extractTourReading(DocumentAiInput document) {
        throw new UnsupportedOperationException(
                "The configured adapter does not support V3.4 extraction");
    }
    /** Whether {@link #prepareSplitSource} and {@link #extractPartialReading} work; callers check this instead of catching. */
    default boolean supportsSplitExtraction() { return false; }
    /** Prepares one document for all calls of a split extraction (copy, digest, data URL, quotation text). */
    default SplitSource prepareSplitSource(DocumentAiInput document) {
        throw new UnsupportedOperationException("Split V3.4 extraction is not supported by this adapter");
    }
    /**
     * One call of a split extraction: a single booking, or the shared document-level facts. Content problems throw
     * {@link InvalidDocumentExtractionException}; provider failures and timeouts propagate unchanged.
     */
    default PartialExtraction extractPartialReading(SplitSource source, SplitCallTarget target) {
        throw new UnsupportedOperationException("Split V3.4 extraction is not supported by this adapter");
    }
    default TourSourceAssessment assessCorrections(DocumentAiInput document, String originalBookings,
            java.util.List<com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change> changes) {
        throw new UnsupportedOperationException("Source reinspection is unavailable");
    }
}
