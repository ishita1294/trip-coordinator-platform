package com.ishita.tripcoordinatorplatform.intelligence.routing;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import java.time.Instant;
import java.util.Optional;

public interface RoutingProvider {
    // Exactly one of departureAt / arrivalBy. Arrival routing is used only for transit.
    Optional<RouteEstimate> route(Coordinates origin, Coordinates destination, TravelMode mode,
                                   Instant departureAt, Instant arrivalBy);
}
