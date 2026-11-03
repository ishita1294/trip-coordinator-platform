package com.ishita.tripcoordinatorplatform.intelligence.commitment;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import java.time.Instant;

public record Commitment(Long itineraryItemId, String title, Instant startsAt, Instant requiredArrivalAt,
        Coordinates destination, String timeZone, String timezoneSource, String arrivalPolicy,
        int arrivalLeadMinutes, String destinationSource, java.time.LocalDateTime scheduledLocalStart) {}
