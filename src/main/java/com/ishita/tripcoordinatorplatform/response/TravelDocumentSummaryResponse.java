package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;

import java.time.Instant;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;

/**
 * One line of the documents list. {@code progressText} is a short live step such as "Read 2 of 3 bookings" while the document is
 * being processed; it is null when the server has no live step, and the screen then shows its usual status text.
 */
public record TravelDocumentSummaryResponse(
        Long id,
        String originalFileName,
        String contentType,
        TravelDocumentProcessingStatus processingStatus,
        TravelDocumentType documentType,
        Instant uploadedAt,
        int processingFailureCount,
        String processingLastErrorCode,
        Instant processingFailedAt,
        String progressText
) {
    /** The summary without a live step. */
    public static TravelDocumentSummaryResponse from(TravelDocument document) {
        return from(document, null);
    }

    /** The summary with the document's live processing step, if there is one. */
    public static TravelDocumentSummaryResponse from(TravelDocument document, String progressText) {
        return new TravelDocumentSummaryResponse(document.getId(), document.getOriginalFileName(),
                document.getContentType(), document.getProcessingStatus(),
                document.getDocumentType(), document.getUploadedAt(), document.getProcessingFailureCount(),
                document.getProcessingLastErrorCode(), document.getProcessingFailedAt(), progressText);
    }
}
