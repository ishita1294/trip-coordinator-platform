package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.google.GoogleRequestBudget;
import com.ishita.tripcoordinatorplatform.intelligence.location.LocationObservation;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlacesProvider;
import java.time.Instant;
import java.util.Map;

/** Explicit planning only: identity is not a geographic observation until Details succeeds. */
final class OriginLocationResolver {
    private OriginLocationResolver() {}

    static ExploreRequest resolve(ExploreRequest request, PlacesProvider places, GoogleRequestBudget budget) {
        if (request.originPlaceId() == null)
            return request;
        var place = budget.timed("originResolution", () -> places.destination(request.originPlaceId()));
        if (place == null || !request.originPlaceId().equals(place.id()) || place.coordinates() == null)
            throw new ProviderUnavailableException("The saved starting place could not be geographically verified. No route was assumed.");
        return request
                .withResolvedOrigin(new LocationObservation(place.coordinates(), LocationObservation.Source.SELECTED, Instant.now(), null));
    }
}
