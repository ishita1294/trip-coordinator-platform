package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;

public record TravelDocumentProcessingResponse(
        Long documentId,
        TravelDocumentProcessingStatus processingStatus,
        TravelDocumentType documentType,
        Long extractionId
) {
}
