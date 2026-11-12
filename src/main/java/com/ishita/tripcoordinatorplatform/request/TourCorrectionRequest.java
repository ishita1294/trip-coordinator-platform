package com.ishita.tripcoordinatorplatform.request;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Explicit request to compare proposed reading corrections with the original upload. */
public record TourCorrectionRequest(Long extractionId, UUID requestId, List<Change> changes) {
    /** User declaration, not an AI extraction correction or independent documentary verification. */
    public record LifecycleClarification(com.ishita.tripcoordinatorplatform.ai.document.ReadingBookingLifecycle.State state,
            String reason,Boolean acknowledgeSourceConflict) {}
    public record Change(Integer bookingIndex, String field, JsonNode originalValue, JsonNode proposedValue) {}
}
