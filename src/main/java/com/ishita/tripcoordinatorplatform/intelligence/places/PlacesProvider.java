package com.ishita.tripcoordinatorplatform.intelligence.places;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.explore.ExplorePreference;
import java.util.List;

public interface PlacesProvider {
    record DestinationQuery(String name, String address, String locality, Coordinates coordinates) {}
    List<PlaceDestination> findDestinations(DestinationQuery query);
    PlaceDestination destination(String placeId);
    default PlaceCandidate candidate(String placeId) { throw new com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException("Candidate details are unavailable"); }
    List<PlaceCandidate> nearby(Coordinates origin, ExplorePreference preference, int radiusMeters, int limit);
    default List<PlaceCandidate> nearby(Coordinates origin, ExplorePreference preference, com.ishita.tripcoordinatorplatform.intelligence.explore.ExploreIntent intent, int radiusMeters, int limit) {
        return nearby(origin, preference, radiusMeters, limit);
    }
    /** One bounded, optional lookup of activity categories underrepresented in a broad pool. */
    default List<PlaceCandidate> nearbyAdditionalActivities(Coordinates origin, int radiusMeters, int limit, java.util.Set<ExplorePreference> represented) {
        return List.of();
    }
}
