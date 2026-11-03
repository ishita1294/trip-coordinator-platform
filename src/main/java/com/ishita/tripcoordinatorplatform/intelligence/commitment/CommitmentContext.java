package com.ishita.tripcoordinatorplatform.intelligence.commitment;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;

// Explicit caller context shared by EXPLORE, PLAN and future WATCH. No HTTP DTO dependency.
public record CommitmentContext(Long userId, String fallbackTimeZone, Selection selection, java.util.Map<Long, String> timeZoneClarifications) {
    public record Selection(Long itineraryItemId, Coordinates destination, String timeZone,
                            Integer arrivalLeadMinutes, String placeId) {}
}
