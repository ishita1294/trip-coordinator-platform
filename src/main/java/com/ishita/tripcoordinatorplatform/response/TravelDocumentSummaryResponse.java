package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;

import java.time.Instant;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;

public record TravelDocumentSummaryResponse(
        Long id,
        String originalFileName,
        String contentType,
        TravelDocumentProcessingStatus processingStatus,
        TravelDocumentType documentType,
        Instant uploadedAt
) {
    public static TravelDocumentSummaryResponse from(TravelDocument document) {
        return new TravelDocumentSummaryResponse(document.getId(), document.getOriginalFileName(),
                document.getContentType(), document.getProcessingStatus(),
                document.getDocumentType(), document.getUploadedAt());
    }
}
