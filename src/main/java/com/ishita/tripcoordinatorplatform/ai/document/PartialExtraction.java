package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

/**
 * Result of one split call after parsing and per-booking validation. Provenance is assigned by the merge, not here.
 * A BOOKING call holds exactly one booking; a SHARED call holds none. Dropped counts out-of-scope supplemental facts.
 */
public record PartialExtraction(SplitCallTarget target, List<TourReading.Booking> bookings,
        List<SupplementalReadingFact> supplementalFacts, String coverageWarning, String providerResponseId,
        CallStats stats, int droppedSupplementalFacts) {
    public PartialExtraction {
        bookings = List.copyOf(bookings);
        supplementalFacts = List.copyOf(supplementalFacts);
    }
}
