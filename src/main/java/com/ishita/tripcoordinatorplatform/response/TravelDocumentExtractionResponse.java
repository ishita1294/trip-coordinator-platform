package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record TravelDocumentExtractionResponse(Long extractionId,
                                               Long documentId,
                                               TravelDocumentType documentType,
                                               Instant createdAt,
                                               JsonNode extractedData) {
}
