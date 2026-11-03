package com.ishita.tripcoordinatorplatform.intelligence.routing;

import java.time.Instant;
import java.util.List;

public record RouteEstimate(TravelMode mode, Instant departureAt, Instant arrivalAt,
                            long distanceMeters, List<String> warnings, List<Attribution> attributions) {
    public record Attribution(String name, String uri) {}
}
