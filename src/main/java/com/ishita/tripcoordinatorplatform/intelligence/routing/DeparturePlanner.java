package com.ishita.tripcoordinatorplatform.intelligence.routing;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Optional;

// Pure derived plan: a future WATCH snapshot can version/store its derived timing,
// but this service neither schedules work nor persists Google response content.
@Service
public class DeparturePlanner {
    public record DeparturePlan(int schemaVersion, Instant calculatedAt, Instant expiresAt,
            Instant latestDepartureAt, Instant requiredArrivalAt, RouteEstimate route, boolean timingVerified) {}
    private final RoutingProvider routes;
    public DeparturePlanner(RoutingProvider routes) { this.routes = routes; }

    public Optional<DeparturePlan> latestDeparture(Coordinates from, Coordinates to, TravelMode mode,
                                                    Instant earliestDeparture, Instant requiredArrival, Instant requestDeadline) {
        requireBudget(requestDeadline);
        Instant calculatedAt = Instant.now();
        if (!requiredArrival.isAfter(earliestDeparture)) return Optional.empty();
        if (mode == TravelMode.TRANSIT) {
            return routes.route(from, to, mode, null, requiredArrival).map(route ->
                    plan(calculatedAt, route.departureAt(), requiredArrival, route,
                            !route.departureAt().isBefore(earliestDeparture)));
        }
        var initial = routes.route(from, to, mode, earliestDeparture, null);
        if (initial.isEmpty()) return Optional.empty();
        RouteEstimate route = initial.get();
        Instant leave = requiredArrival.minus(Duration.between(route.departureAt(), route.arrivalAt()));
        if (mode == TravelMode.WALK) {
            RouteEstimate shifted = new RouteEstimate(mode, leave, requiredArrival, route.distanceMeters(), route.warnings(), route.attributions());
            return Optional.of(plan(calculatedAt, leave, requiredArrival, shifted, true));
        }
        // Traffic is departure-dependent. Bound iteration and never claim an unchecked estimate fits.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (leave.isBefore(earliestDeparture)) return Optional.of(plan(calculatedAt, leave, requiredArrival, route, false));
            requireBudget(requestDeadline);
            var estimate = routes.route(from, to, mode, leave, null);
            if (estimate.isEmpty()) return Optional.empty();
            route = estimate.get();
            if (!route.arrivalAt().isAfter(requiredArrival)) {
                return Optional.of(plan(calculatedAt, route.departureAt(), requiredArrival, route, true));
            }
            leave = leave.minus(Duration.between(requiredArrival, route.arrivalAt()));
        }
        return Optional.of(plan(calculatedAt, leave, requiredArrival, route, false));
    }
    private void requireBudget(Instant deadline) {
        if (Instant.now().isAfter(deadline)) throw new com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException("EXPLORE request time budget reached");
    }
    private DeparturePlan plan(Instant now, Instant leave, Instant arrival, RouteEstimate route, boolean verified) {
        return new DeparturePlan(1, now, now.plusSeconds(300), leave, arrival, route, verified);
    }
}
