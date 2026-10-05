package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;

import java.time.Instant;

public record TravelDocumentSummaryResponse(
        Long id,
        String originalFileName,
        String contentType,
        TravelDocumentProcessingStatus processingStatus,
        TravelDocumentType documentType,
        Instant uploadedAt
) {
}
