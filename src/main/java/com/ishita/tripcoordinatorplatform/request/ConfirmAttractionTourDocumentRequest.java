package com.ishita.tripcoordinatorplatform.request;

import java.util.List;

// Coordination metadata only; the client cannot supply extracted booking facts.
public record ConfirmAttractionTourDocumentRequest(List<Long> participantIds) {
}
