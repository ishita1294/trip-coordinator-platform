package com.ishita.tripcoordinatorplatform.intelligence.location;

import java.time.Instant;

// Request-scoped observation; never stored as device-location history.
public record LocationObservation(Coordinates coordinates, Source source, Instant observedAt, Double accuracyMeters) {
    public enum Source { DEVICE, SELECTED }
    public LocationObservation {
        if (coordinates == null || source == null || observedAt == null) {
            throw new IllegalArgumentException("Location coordinates, source and observation timestamp are required");
        }
        if (accuracyMeters != null && (!Double.isFinite(accuracyMeters) || accuracyMeters < 0)) {
            throw new IllegalArgumentException("Location accuracy must be a nonnegative distance");
        }
    }
}
