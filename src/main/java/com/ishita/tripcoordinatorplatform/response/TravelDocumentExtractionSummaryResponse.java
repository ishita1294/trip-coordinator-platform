package com.ishita.tripcoordinatorplatform.response;

import java.time.Instant;

public record TravelDocumentExtractionSummaryResponse(Long extractionId, Instant createdAt) {
}
