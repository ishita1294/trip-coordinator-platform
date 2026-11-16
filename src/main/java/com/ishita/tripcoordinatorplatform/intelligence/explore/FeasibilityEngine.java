package com.ishita.tripcoordinatorplatform.intelligence.explore;

import org.springframework.context.annotation.Profile;
import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility;

import com.ishita.tripcoordinatorplatform.intelligence.commitment.Commitment;
import com.ishita.tripcoordinatorplatform.intelligence.location.LocationObservation;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate;
import com.ishita.tripcoordinatorplatform.intelligence.routing.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility.*;

/**
 * Decides whether a traveler can visit a place before a fixed commitment: checks the route there, the opening hours and the time left, and
 * explains each decision. Not loaded in the document worker.
 */
@Profile("!document-worker")
@Service
public class FeasibilityEngine {
    private final RoutingProvider routes;
    private final DeparturePlanner departures;

    /** Creates the engine with the routing provider and the departure planner. */
    public FeasibilityEngine(RoutingProvider routes, DeparturePlanner departures) {
        this.routes = routes;
        this.departures = departures;
    }

    public VisitFeasibility assess(PlaceCandidate place, LocationObservation location, boolean fresh, TravelMode mode,
            Commitment commitment, Integer minimumMinutes, Instant now, Instant requestDeadline) {
        return assess(place, location, fresh, mode, commitment, minimumMinutes, now, requestDeadline, null);
    }

    public VisitFeasibility assess(PlaceCandidate place, LocationObservation location, boolean fresh, TravelMode mode,
            Commitment commitment, Integer minimumMinutes, Instant now, Instant requestDeadline, Instant visitCutoff) {
        return assess(place, location, fresh, mode, mode, commitment, minimumMinutes, now, requestDeadline, visitCutoff);
    }

    /**
     * Decides whether the place can be visited between now and the commitment: checks the route there, the opening hours,
     * the time left for a visit of at least the minimum length and the route to the commitment, and gives the reasons for
     * the result. A stale location adds a warning, and the work stops when the request deadline has passed.
     */
    public VisitFeasibility assess(PlaceCandidate place, LocationObservation location, boolean fresh, TravelMode mode,
            TravelMode onwardMode,
            Commitment commitment, Integer minimumMinutes, Instant now, Instant requestDeadline, Instant visitCutoff) {
        List<Reason> reasons = new ArrayList<>();
        if (!fresh)
            reasons.add(new Reason("LOCATION_STALE_OR_IMPRECISE", "Update your location before relying on these routes."));
        RouteEstimate outbound = null;
        DeparturePlanner.DeparturePlan plan = null;
        Instant start = null, end = null;
        Long minutes = null;
        boolean violation = false;
        try {
            requireBudget(requestDeadline);
            var route = routes.route(location.coordinates(), place.coordinates(), mode, now, null);
            if (route.isEmpty()) {
                reasons.add(new Reason("OUTBOUND_ROUTE_UNKNOWN",
                        "No route was returned for the requested mode; no alternative mode was substituted."));
            } else {
                outbound = route.get();
                start = outbound.arrivalAt();
                if (!outbound.warnings().isEmpty())
                    reasons.add(new Reason("ROUTE_WARNING", "Review the provider's route warnings."));
                if (!start.isBefore(commitment.requiredArrivalAt())) {
                    violation = true;
                    reasons.add(new Reason("ARRIVAL_DEADLINE_MISSED",
                            "Reaching this place would already miss the commitment arrival deadline."));
                } else if (commitment.destination() == null) {
                    reasons.add(new Reason("COMMITMENT_DESTINATION_UNKNOWN",
                            "Select the actual commitment meeting/entry destination to calculate the return route."));
                } else {
                    var planned = departures.latestDeparture(place.coordinates(), commitment.destination(), onwardMode,
                            start, commitment.requiredArrivalAt(), requestDeadline);
                    if (planned.isEmpty()) {
                        reasons.add(new Reason("RETURN_ROUTE_UNKNOWN", "No return route in the requested mode could be established."));
                    } else {
                        plan = planned.get();
                        end = plan.latestDepartureAt();
                        if (!plan.timingVerified())
                            reasons.add(new Reason("RETURN_TIMING_UNVERIFIED",
                                    "The departure-dependent return timing could not be verified within the bounded routing requests."));
                        if (!plan.route().warnings().isEmpty())
                            reasons.add(new Reason("RETURN_ROUTE_WARNING", "Review return-route warnings."));
                        if (plan.timingVerified() && !end.isAfter(start)) {
                            violation = true;
                            reasons.add(new Reason("NO_VISITING_WINDOW", "Travel leaves no positive visiting window."));
                        }
                        // Day end limits visiting, not arrival at an invented accommodation.
                        if (visitCutoff != null && visitCutoff.isBefore(end))
                            end = visitCutoff;
                        if (!end.isAfter(start)) {
                            violation = true;
                            reasons.add(new Reason("DAILY_CUTOFF_REACHED", "Travel leaves no visit before your exploration day ends."));
                        }
                        if (place.hours() == null) {
                            reasons.add(
                                    new Reason("OPENING_HOURS_UNKNOWN", "Date-specific opening hours or timezone could not be verified."));
                        } else if (start.isBefore(place.hours().coverageStart()) || !start.isBefore(place.hours().coverageEnd())) {
                            reasons.add(new Reason("OPENING_HOURS_OUTSIDE_COVERAGE",
                                    "The proposed visit is outside the returned opening-hours coverage."));
                        } else {
                            Instant bestStart = null, bestEnd = null;
                            for (var interval : place.hours().intervals()) {
                                Instant opens = start.isAfter(interval.opensAt()) ? start : interval.opensAt();
                                Instant closes = end.isBefore(interval.closesAt()) ? end : interval.closesAt();
                                if (closes.isAfter(opens) && (bestStart == null
                                        || Duration.between(opens, closes).compareTo(Duration.between(bestStart, bestEnd)) > 0)) {
                                    bestStart = opens;
                                    bestEnd = closes;
                                }
                            }
                            if (bestStart == null) {
                                if (end.isAfter(place.hours().coverageEnd())) {
                                    reasons.add(new Reason("OPENING_HOURS_OUTSIDE_COVERAGE",
                                            "Hours do not cover the entire possible window; later openings are unknown."));
                                } else if (plan.timingVerified()) {
                                    violation = true;
                                    reasons.add(new Reason("CLOSED_DURING_WINDOW",
                                            "Published hours provide no open interval before the required departure."));
                                } else {
                                    reasons.add(new Reason("OPEN_WINDOW_UNVERIFIED",
                                            "An opening window cannot be established until return timing is known."));
                                }
                            } else {
                                start = bestStart;
                                end = bestEnd;
                                if (end.isAfter(place.hours().coverageEnd()))
                                    reasons.add(
                                            new Reason("OPENING_HOURS_OUTSIDE_COVERAGE", "Opening-hour coverage ends before this window."));
                            }
                        }
                        // Recheck once at the final departure after BOTH daily cutoff and hours clipping.
                        if (end.isAfter(start) && end.isBefore(plan.latestDepartureAt())) {
                            if (onwardMode == TravelMode.WALK) {
                                var previous = plan.route();
                                var shifted = new RouteEstimate(onwardMode, end,
                                        end.plus(Duration.between(previous.departureAt(), previous.arrivalAt())), previous.distanceMeters(),
                                        previous.warnings(), previous.attributions());
                                plan = new DeparturePlanner.DeparturePlan(1, Instant.now(), Instant.now().plusSeconds(300), end,
                                        commitment.requiredArrivalAt(), shifted, true);
                            } else {
                                requireBudget(requestDeadline);
                                var earlier = routes.route(place.coordinates(), commitment.destination(), onwardMode, end, null);
                                if (earlier.isEmpty())
                                    reasons.add(new Reason("EARLIER_RETURN_UNVERIFIED",
                                            "Onward travel at the daily cutoff or closing time needs verification."));
                                else {
                                    plan = new DeparturePlanner.DeparturePlan(1, Instant.now(), Instant.now().plusSeconds(300), end,
                                            commitment.requiredArrivalAt(), earlier.get(), true);
                                    if (earlier.get().arrivalAt().isAfter(commitment.requiredArrivalAt())) {
                                        violation = true;
                                        reasons.add(new Reason("ARRIVAL_DEADLINE_MISSED",
                                                "Onward travel at the daily cutoff or closing time misses the required arrival."));
                                    }
                                    if (!earlier.get().warnings().isEmpty())
                                        reasons.add(new Reason("RETURN_ROUTE_WARNING", "Review return-route warnings."));
                                }
                            }
                        }
                        if (end.isAfter(start)) {
                            minutes = Duration.between(start, end).toMinutes();
                            Duration required = Duration.ofMinutes(minimumMinutes == null ? 0 : minimumMinutes);
                            if (place.verifiedDuration() != null && place.durationSource() != null) {
                                if (place.verifiedDuration().compareTo(required) > 0)
                                    required = place.verifiedDuration();
                            }
                            if (plan.timingVerified() && Duration.between(start, end).compareTo(required) < 0) {
                                violation = true;
                                reasons.add(new Reason("MINIMUM_DURATION_NOT_MET",
                                        "The available window is shorter than the requested minimum or verified experience duration."));
                            }
                        }
                    }
                }
            }
        } catch (ProviderUnavailableException exception) {
            reasons.add(new Reason("ROUTING_PROVIDER_UNAVAILABLE", exception.getMessage()));
        }
        if (place.admissionRequiresVerification())
            reasons.add(
                    new Reason("ADMISSION_UNVERIFIED", "Opening hours do not establish ticket/entry availability. Verify with the venue."));
        Status status = violation ? Status.INFEASIBLE : reasons.isEmpty() ? Status.FEASIBLE : Status.CONDITIONAL;
        String explanation = com.ishita.tripcoordinatorplatform.intelligence.places.ActivityRelevance.evidence(place);
        return new VisitFeasibility(place, status, start, end, minutes, outbound, plan, List.copyOf(reasons), explanation);
    }

    /** No onward route, departure deadline or available-time window exists in this workflow. */
    /**
     * Assesses a place with no next commitment: needs a verified activity reason and a route from the starting location, and gives the
     * reasons when it is unavailable. A stale location adds a warning.
     */
    public OpenEndedAssessment assessOpenEnded(PlaceCandidate place, LocationObservation location, boolean fresh,
            TravelMode mode, Integer minimumMinutes, Instant departure, Instant requestDeadline) {
        List<Reason> reasons = new ArrayList<>();
        if (place.interests().isEmpty())
            return new OpenEndedAssessment(place, OpenEndedAssessment.Suitability.UNAVAILABLE, null, "NOT_ASSESSED",
                    List.of(new Reason("ACTIVITY_RELEVANCE_UNVERIFIED",
                            "Provider categories do not establish an activity worth exploring; no route was requested.")),
                    "No verified activity relevance.");
        if (!fresh)
            reasons.add(
                    new Reason("LOCATION_STALE_OR_IMPRECISE", "Refresh or reconfirm the starting location before relying on the route."));
        RouteEstimate outbound = null;
        String hours = "UNKNOWN";
        boolean unavailable = false;
        try {
            requireBudget(requestDeadline);
            outbound = routes.route(location.coordinates(), place.coordinates(), mode, departure, null).orElse(null);
            if (outbound == null)
                reasons.add(new Reason("OUTBOUND_ROUTE_UNKNOWN", "No route was returned in the requested mode. No mode was substituted."));
            else {
                if (!outbound.warnings().isEmpty())
                    reasons.add(new Reason("ROUTE_WARNING", "Review the route provider warnings."));
                Instant arrival = outbound.arrivalAt();
                if (place.hours() == null)
                    reasons.add(new Reason("OPENING_HOURS_UNKNOWN", "Date-specific opening hours could not be verified."));
                else if (arrival.isBefore(place.hours().coverageStart()) || !arrival.isBefore(place.hours().coverageEnd())) {
                    hours = "OUTSIDE_VERIFIED_COVERAGE";
                    reasons.add(new Reason("OPENING_HOURS_OUTSIDE_COVERAGE", "Published hours do not cover your estimated arrival."));
                } else {
                    var interval = place.hours().intervals().stream()
                            .filter(i -> !arrival.isBefore(i.opensAt()) && arrival.isBefore(i.closesAt())).findFirst().orElse(null);
                    if (interval == null) {
                        hours = "CLOSED_AT_ARRIVAL";
                        unavailable = true;
                        reasons.add(new Reason("CLOSED_AT_ARRIVAL",
                                "Published hours show the place closed at estimated arrival. Try another departure; no waiting or visit "
                                        + "duration was invented."));
                    } else {
                        hours = "PUBLISHED_OPEN_AT_ARRIVAL";
                        Duration required = Duration.ofMinutes(minimumMinutes == null ? 0 : minimumMinutes);
                        if (place.verifiedDuration() != null && place.durationSource() != null
                                && place.verifiedDuration().compareTo(required) > 0)
                            required = place.verifiedDuration();
                        if (arrival.plus(required).isAfter(interval.closesAt())) {
                            unavailable = true;
                            reasons.add(new Reason("MINIMUM_DURATION_NOT_MET",
                                    "Published closing time prevents the requested minimum or verified experience duration at this "
                                            + "departure."));
                        }
                    }
                }
            }
        } catch (ProviderUnavailableException failure) {
            reasons.add(new Reason("ROUTING_PROVIDER_UNAVAILABLE", failure.getMessage()));
        }
        boolean lateNight = false;
        if (outbound != null && place.timeZone() != null) {
            try {
                int hour = outbound.arrivalAt().atZone(ZoneId.of(place.timeZone())).getHour();
                lateNight = hour >= 22 || hour < 6;
            } catch (DateTimeException invalidZone) {
                /* Missing time-of-day evidence remains unknown. */ }
        }
        if (lateNight && !hours.equals("PUBLISHED_OPEN_AT_ARRIVAL") && !unavailable)
            reasons.add(new Reason("LATE_NIGHT_OPENING_UNVERIFIED",
                    "Estimated arrival is late at night in the provider's destination timezone. Do not treat this as a current option "
                            + "until actual opening/access is verified."));
        if (lateNight && hours.equals("PUBLISHED_OPEN_AT_ARRIVAL") && place.interests().contains(ExplorePreference.OUTDOORS))
            reasons.add(new Reason("NIGHTTIME_ACCESS_UNVERIFIED",
                    "Published opening hours cover nighttime arrival, but they do not verify lighting or current access conditions."));
        if (place.admissionRequiresVerification())
            reasons.add(new Reason("ADMISSION_UNVERIFIED", "Published hours do not establish entry or ticket availability."));
        return new OpenEndedAssessment(place,
                unavailable ? OpenEndedAssessment.Suitability.UNAVAILABLE
                        : reasons.isEmpty() ? OpenEndedAssessment.Suitability.SUITABLE : OpenEndedAssessment.Suitability.VERIFY_DETAILS,
                outbound, hours, List.copyOf(reasons),
                com.ishita.tripcoordinatorplatform.intelligence.places.ActivityRelevance.evidence(place));
    }

    /** Returns the direct route between two points for a mode and departure time, if the provider has one. */
    public Optional<RouteEstimate> directRoute(com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates origin,
            com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates destination, TravelMode mode, Instant departure) {
        return routes.route(origin, destination, mode, departure, null);
    }

    /** Finite trip-day window with no fictional onward commitment or return journey. */
    public VisitFeasibility assessWithoutNextCommitment(PlaceCandidate place, LocationObservation location, boolean fresh, TravelMode mode,
            Integer minimumMinutes, Instant departure, Instant windowEnd, Instant requestDeadline) {
        var outbound = assessOpenEnded(place, location, fresh, mode, minimumMinutes, departure, requestDeadline);
        var reasons = new ArrayList<>(outbound.reasons());
        Instant start = outbound.outbound() == null ? null : outbound.outbound().arrivalAt();
        Instant end = windowEnd;
        boolean impossible = outbound.suitability() == OpenEndedAssessment.Suitability.UNAVAILABLE;
        if (start != null) {
            if (!start.isBefore(windowEnd)) {
                impossible = true;
                reasons.add(new Reason("WINDOW_END_MISSED", "Travel reaches the destination after the selected window ends."));
            }
            if (place.hours() != null) {
                final Instant arrival = start;
                var interval = place.hours().intervals().stream()
                        .filter(i -> !arrival.isBefore(i.opensAt()) && arrival.isBefore(i.closesAt())).findFirst().orElse(null);
                if (interval != null && interval.closesAt().isBefore(end))
                    end = interval.closesAt();
            }
            Duration required = Duration.ofMinutes(minimumMinutes == null ? 0 : minimumMinutes);
            if (place.verifiedDuration() != null && place.durationSource() != null && place.verifiedDuration().compareTo(required) > 0)
                required = place.verifiedDuration();
            if (start.plus(required).isAfter(end)) {
                impossible = true;
                reasons.add(new Reason("MINIMUM_DURATION_NOT_MET",
                        "Travel and the requested or verified visit duration exceed the selected window."));
            }
        }
        Long minutes = start == null ? null : Math.max(0, Duration.between(start, end).toMinutes());
        Status status = impossible ? Status.INFEASIBLE : reasons.isEmpty() ? Status.FEASIBLE : Status.CONDITIONAL;
        reasons.add(new Reason("NO_ONWARD_COMMITMENT",
                "This finite window has no next commitment; no onward journey or arrival deadline was invented."));
        return new VisitFeasibility(place, status, start, start == null ? null : end, minutes, outbound.outbound(), null,
                List.copyOf(reasons), outbound.explanation());
    }

    /** Stops further work when the request's time budget has run out. */
    private void requireBudget(Instant deadline) {
        if (Instant.now().isAfter(deadline))
            throw new ProviderUnavailableException("EXPLORE request time budget reached");
    }
}
