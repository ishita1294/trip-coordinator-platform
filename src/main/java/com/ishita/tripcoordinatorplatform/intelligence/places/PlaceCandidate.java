package com.ishita.tripcoordinatorplatform.intelligence.places;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Provider-neutral, ephemeral facts. Duration is null unless verified by a source.
public record PlaceCandidate(String id, String name, String address, Coordinates coordinates,
        String mapsUri, List<String> types, OpeningHours hours, Duration verifiedDuration,
        String durationSource, boolean admissionRequiresVerification, List<Attribution> attributions, String primaryType, Double rating, Integer reviewCount, java.util.Set<com.ishita.tripcoordinatorplatform.intelligence.explore.ExplorePreference> interests,
        java.util.Set<com.ishita.tripcoordinatorplatform.intelligence.explore.ExploreIntent> intents, String timeZone) {
    public PlaceCandidate(String id, String name, String address, Coordinates coordinates, String mapsUri, List<String> types, OpeningHours hours, Duration verifiedDuration, String durationSource, boolean admissionRequiresVerification, List<Attribution> attributions, String primaryType, Double rating, Integer reviewCount, java.util.Set<com.ishita.tripcoordinatorplatform.intelligence.explore.ExplorePreference> interests, java.util.Set<com.ishita.tripcoordinatorplatform.intelligence.explore.ExploreIntent> intents) {
        this(id, name, address, coordinates, mapsUri, types, hours, verifiedDuration, durationSource, admissionRequiresVerification, attributions, primaryType, rating, reviewCount, interests, intents, null);
    }
    public record Attribution(String name, String uri) {}
    public record OpeningInterval(Instant opensAt, Instant closesAt) {}
    public record OpeningHours(Instant coverageStart, Instant coverageEnd, List<OpeningInterval> intervals) {}
}
