package com.ishita.tripcoordinatorplatform.intelligence.google;

import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.routing.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public class GoogleRoutingProvider implements RoutingProvider {
    private final GoogleMapsClient client;
    public GoogleRoutingProvider(GoogleMapsClient client) { this.client = client; }

    @Override
    public Optional<RouteEstimate> route(Coordinates origin, Coordinates destination, TravelMode mode,
                                         Instant departureAt, Instant arrivalBy) {
        try {
            return compute(origin, destination, mode, departureAt, arrivalBy);
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderUnavailableException("Google returned an incomplete or invalid route");
        }
    }

    private Optional<RouteEstimate> compute(Coordinates origin, Coordinates destination, TravelMode mode,
                                            Instant departureAt, Instant arrivalBy) {
        if ((departureAt == null) == (arrivalBy == null) || (arrivalBy != null && mode != TravelMode.TRANSIT)) {
            throw new IllegalArgumentException("Use departure routing, or transit-only arrival routing");
        }
        Instant now = Instant.now();
        if (mode == TravelMode.TRANSIT && (arrivalBy != null ? arrivalBy : departureAt).isAfter(now.plus(Duration.ofDays(100)))) {
            throw new ProviderUnavailableException("Transit schedules are unavailable more than 100 days ahead");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("origin", waypoint(origin)); body.put("destination", waypoint(destination));
        body.put("travelMode", mode.name()); body.put("computeAlternativeRoutes", false);
        Instant effectiveDeparture = departureAt == null ? null
                : (departureAt.isBefore(now) ? now : departureAt);
        if (mode != TravelMode.WALK) {
            if (arrivalBy != null) body.put("arrivalTime", arrivalBy.toString());
            else {
                effectiveDeparture = client.departureAt(departureAt);
                body.put("departureTime", effectiveDeparture.toString());
            }
        }
        if (mode == TravelMode.DRIVE) body.put("routingPreference", "TRAFFIC_AWARE");
        String mask = "routes.duration,routes.distanceMeters,routes.warnings";
        if (mode == TravelMode.TRANSIT) mask += ",routes.legs.steps.staticDuration,"
                + "routes.legs.steps.transitDetails.stopDetails.departureTime,"
                + "routes.legs.steps.transitDetails.stopDetails.arrivalTime,"
                + "routes.legs.steps.transitDetails.transitLine.agencies";
        var reply = client.postRoute("https://routes.googleapis.com/directions/v2:computeRoutes", mask, body);
        var response=reply.response();
        if(reply.departureAt()!=null) effectiveDeparture=reply.departureAt();
        var routes = response.path("routes");
        if (!routes.isArray() || routes.isEmpty()) return Optional.empty();
        var route = routes.get(0);
        List<String> warnings = new ArrayList<>(); route.path("warnings").forEach(w -> warnings.add(w.asText()));
        List<RouteEstimate.Attribution> attributions = new ArrayList<>();
        Instant departure = effectiveDeparture, arrival;
        if (mode == TravelMode.TRANSIT) {
            long before = 0, after = 0;
            Instant firstDeparture = null, lastArrival = null;
            for (var leg : route.path("legs")) for (var step : leg.path("steps")) {
                var details = step.path("transitDetails");
                if (!details.isMissingNode() && !details.isNull()) {
                    Instant boards = Instant.parse(details.path("stopDetails").path("departureTime").asText());
                    if (firstDeparture == null) firstDeparture = boards.minusSeconds(before);
                    lastArrival = Instant.parse(details.path("stopDetails").path("arrivalTime").asText());
                    after = 0;
                    details.path("transitLine").path("agencies").forEach(a -> attributions.add(
                            new RouteEstimate.Attribution(a.path("name").asText(), a.path("uri").asText())));
                } else {
                    long seconds = seconds(step.path("staticDuration").asText());
                    if (firstDeparture == null) before += seconds; else after += seconds;
                }
            }
            // A WALK-only result is not silently accepted as the requested TRANSIT mode.
            if (firstDeparture == null || lastArrival == null) return Optional.empty();
            departure = firstDeparture; arrival = lastArrival.plusSeconds(after);
            if (departureAt != null && departure.isBefore(departureAt)) return Optional.empty();
            if (arrivalBy != null && arrival.isAfter(arrivalBy)) return Optional.empty();
        } else {
            arrival = departure.plusSeconds(seconds(route.path("duration").asText()));
        }
        return Optional.of(new RouteEstimate(mode, departure, arrival,
                route.path("distanceMeters").asLong(), List.copyOf(warnings), List.copyOf(attributions)));
    }

    private Map<String, Object> waypoint(Coordinates coordinates) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", coordinates.latitude(), "longitude", coordinates.longitude())));
    }
    private long seconds(String value) {
        if (!value.matches("[0-9]+(\\.[0-9]+)?s")) throw new ProviderUnavailableException("Google returned an incomplete route duration");
        return new java.math.BigDecimal(value.substring(0, value.length() - 1))
                .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
    }
}
