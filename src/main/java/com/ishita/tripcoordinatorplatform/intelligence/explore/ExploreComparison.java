package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility;
import com.ishita.tripcoordinatorplatform.intelligence.routing.RouteEstimate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Response projections only: no routing, scheduling decisions or AI-parsed numbers. */
public final class ExploreComparison {
    private ExploreComparison() {}
    public record Facts(String placeId, VisitFeasibility.Status status, Long outboundTravelSeconds,
                        Long onwardTravelSeconds, Long totalTravelSeconds, Long availableVisitMinutes,
                        Instant departureAt, Instant latestDepartureAt, Instant requiredArrivalAt, boolean onwardTimingVerified,
                        String openingHoursConfidence, List<VisitFeasibility.Reason> reasons) {}
    public static Facts withStart(Facts facts,ExplorationStartPlanner.Plan start) {
        Long total=facts.totalTravelSeconds();
        if(total!=null && start.repositioning()!=null) total+=java.time.Duration.between(start.repositioning().departureAt(),start.repositioning().arrivalAt()).toSeconds();
        return new Facts(facts.placeId(),facts.status(),facts.outboundTravelSeconds(),facts.onwardTravelSeconds(),total,facts.availableVisitMinutes(),facts.departureAt(),facts.latestDepartureAt(),facts.requiredArrivalAt(),facts.onwardTimingVerified(),facts.openingHoursConfidence(),facts.reasons());
    }
    /** All numeric changes are second minus first; unknown inputs produce null, never zero. */
    public record Difference(Long outboundTravelSecondsChange, Long onwardTravelSecondsChange,
                             Long totalTravelSecondsChange, Long visitingMinutesChange,
                             Long departureSecondsChange, Long latestDepartureSecondsChange, Long requiredArrivalSecondsChange,
                             VisitFeasibility.Status firstStatus, VisitFeasibility.Status secondStatus,
                             List<VisitFeasibility.Reason> newUncertainty, List<VisitFeasibility.Reason> resolvedUncertainty,
                             List<VisitFeasibility.Reason> notRecheckedUncertainty) {}
    public static Facts facts(VisitFeasibility value) {
        Long outbound = seconds(value.outbound());
        var plan = value.returnPlan();
        boolean earlierReturnUnknown = value.reasons().stream().anyMatch(r -> r.code().equals("EARLIER_RETURN_UNVERIFIED"));
        Long onward = plan == null || earlierReturnUnknown ? null : seconds(plan.route());
        Instant latestDeparture = plan == null ? null : plan.latestDepartureAt();
        if (latestDeparture != null && value.visitEndsAt() != null && value.visitEndsAt().isBefore(latestDeparture)) latestDeparture = value.visitEndsAt();
        String hours = value.place().hours() == null ? "UNKNOWN" : "NOT_ASSESSED_FOR_WINDOW";
        if (value.reasons().stream().anyMatch(r -> r.code().equals("CLOSED_DURING_WINDOW"))) hours = "CLOSED_DURING_WINDOW";
        else if (value.reasons().stream().anyMatch(r -> r.code().equals("OPENING_HOURS_OUTSIDE_COVERAGE"))) hours = "OUTSIDE_VERIFIED_COVERAGE";
        else if (value.place().hours() != null && value.visitStartsAt() != null && value.visitEndsAt() != null
                && value.visitEndsAt().isAfter(value.visitStartsAt())
                && value.place().hours().intervals().stream().anyMatch(interval -> !value.visitStartsAt().isBefore(interval.opensAt()) && !value.visitEndsAt().isAfter(interval.closesAt()))) hours = "PUBLISHED_OPEN_INTERVAL";
        return new Facts(value.place().id(), value.status(), outbound, onward,
                value.reasons().stream().anyMatch(r->r.code().equals("NO_ONWARD_COMMITMENT")) ? outbound : outbound == null || onward == null ? null : outbound + onward, value.availableVisitMinutes(),
                value.outbound() == null ? null : value.outbound().departureAt(),
                latestDeparture, plan == null ? null : plan.requiredArrivalAt(),
                plan != null && plan.timingVerified() && !earlierReturnUnknown, hours, value.reasons());
    }
    public static Facts facts(VisitFeasibility value, Instant requestedDeparture, Instant requiredArrival) {
        var facts = facts(value);
        return new Facts(facts.placeId(), facts.status(), facts.outboundTravelSeconds(), facts.onwardTravelSeconds(),
                facts.totalTravelSeconds(), facts.availableVisitMinutes(), requestedDeparture, facts.latestDepartureAt(),
                requiredArrival, facts.onwardTimingVerified(), facts.openingHoursConfidence(), facts.reasons());
    }
    public static Facts facts(OpenEndedAssessment value, Instant requestedDeparture) {
        Long outbound = seconds(value.outbound());
        return new Facts(value.place().id(), null, outbound, null, outbound, null, requestedDeparture, null, null, false, value.openingHoursEvidence(), value.reasons());
    }
    public static Difference difference(Facts first, Facts second) {
        Set<String> firstCodes = codes(first), secondCodes = codes(second);
        return new Difference(change(first.outboundTravelSeconds(), second.outboundTravelSeconds()),
                change(first.onwardTravelSeconds(), second.onwardTravelSeconds()),
                change(first.totalTravelSeconds(), second.totalTravelSeconds()),
                change(first.availableVisitMinutes(), second.availableVisitMinutes()),
                instantChange(first.departureAt(), second.departureAt()), instantChange(first.latestDepartureAt(), second.latestDepartureAt()),
                instantChange(first.requiredArrivalAt(), second.requiredArrivalAt()), first.status(), second.status(),
                second.reasons().stream().filter(r -> !firstCodes.contains(r.code())).toList(),
                first.reasons().stream().filter(r -> !secondCodes.contains(r.code()) && resolved(r.code(), second)).toList(),
                first.reasons().stream().filter(r -> !secondCodes.contains(r.code()) && !resolved(r.code(), second)).toList());
    }
    // A skipped downstream assessment is not proof that its previous uncertainty is resolved.
    private static boolean resolved(String code, Facts second) {
        boolean windowKnown = second.availableVisitMinutes() != null && second.onwardTimingVerified();
        return switch (code) {
            case "OPENING_HOURS_UNKNOWN", "OPENING_HOURS_OUTSIDE_COVERAGE", "OPEN_WINDOW_UNVERIFIED", "CLOSED_DURING_WINDOW" -> second.openingHoursConfidence().equals("PUBLISHED_OPEN_AT_ARRIVAL") || second.openingHoursConfidence().equals("PUBLISHED_OPEN_INTERVAL") || second.openingHoursConfidence().equals("CLOSED_DURING_WINDOW");
            case "OUTBOUND_ROUTE_UNKNOWN", "ROUTE_WARNING" -> second.outboundTravelSeconds() != null;
            case "RETURN_ROUTE_UNKNOWN", "RETURN_TIMING_UNVERIFIED", "EARLIER_RETURN_UNVERIFIED", "RETURN_ROUTE_WARNING", "COMMITMENT_DESTINATION_UNKNOWN" -> second.onwardTravelSeconds() != null && second.onwardTimingVerified();
            case "ROUTING_PROVIDER_UNAVAILABLE" -> second.outboundTravelSeconds() != null && (second.status() == null || second.onwardTimingVerified());
            case "ARRIVAL_DEADLINE_MISSED", "NO_VISITING_WINDOW", "MINIMUM_DURATION_NOT_MET" -> windowKnown;
            case "LOCATION_STALE_OR_IMPRECISE" -> true; // Assessed independently of routing in every scenario.
            case "ADMISSION_UNVERIFIED" -> false; // Google cannot establish admission availability.
            default -> second.status() == VisitFeasibility.Status.FEASIBLE;
        };
    }
    public static String tradeOff(String firstName, String secondName, Difference difference) {
        if (difference.firstStatus() == null && difference.secondStatus() == null) return difference.outboundTravelSecondsChange() == null ? "Outbound travel difference cannot be verified." : secondName + " has " + Math.abs(difference.outboundTravelSecondsChange()) / 60 + " minutes " + (difference.outboundTravelSecondsChange() == 0 ? "difference in" : difference.outboundTravelSecondsChange() > 0 ? "more" : "less") + " estimated outbound travel than " + firstName + ". No schedule fit or available visiting window was assessed.";
        if (difference.visitingMinutesChange() == null || difference.totalTravelSecondsChange() == null)
            return "Missing travel or visiting evidence prevents a complete numerical comparison.";
        String visiting = difference.visitingMinutesChange() > 0 ? "more" : difference.visitingMinutesChange() < 0 ? "less" : "the same";
        String travel = difference.totalTravelSecondsChange() > 0 ? "more" : difference.totalTravelSecondsChange() < 0 ? "less" : "the same";
        return secondName + " has " + visiting + " estimated visiting time and " + travel + " total travel than " + firstName + " in this scenario. Feasibility and verification requirements still apply.";
    }
    public static String tradeOff(String firstName, String secondName, Difference difference,
                                 Facts first, Facts second, com.ishita.tripcoordinatorplatform.intelligence.routing.TravelMode mode) {
        if (first.status() == null && second.status() == null && difference.outboundTravelSecondsChange() != null
                && Math.abs(difference.outboundTravelSecondsChange()) >= 60) {
            boolean firstShorter = difference.outboundTravelSecondsChange() > 0;
            Facts shorter = firstShorter ? first : second;
            if (!shorter.openingHoursConfidence().equals("CLOSED_AT_ARRIVAL")
                    && shorter.reasons().stream().noneMatch(r -> Set.of("MINIMUM_DURATION_NOT_MET", "ROUTE_WARNING", "OUTBOUND_ROUTE_UNKNOWN", "ROUTING_PROVIDER_UNAVAILABLE").contains(r.code()))) {
                String name = firstShorter ? firstName : secondName;
                String travel = mode == com.ishita.tripcoordinatorplatform.intelligence.routing.TravelMode.WALK ? "walk" : "journey";
                // This is advice for an explicit convenience priority, not an overall quality winner.
                return "For a shorter " + travel + ", choose " + name + ": about "
                        + Math.abs(difference.outboundTravelSecondsChange()) / 60 + " minutes less travel. "
                        + (shorter.reasons().isEmpty() && shorter.openingHoursConfidence().equals("PUBLISHED_OPEN_AT_ARRIVAL")
                           ? "Your preference for the other experience may outweigh convenience."
                           : "Check its hours and access first; your preference for the other experience may outweigh convenience.");
            }
        }
        if (first.status() == VisitFeasibility.Status.FEASIBLE && second.status() == VisitFeasibility.Status.FEASIBLE
                && first.onwardTimingVerified() && second.onwardTimingVerified()
                && difference.totalTravelSecondsChange() != null && difference.visitingMinutesChange() != null) {
            boolean firstBetter = difference.totalTravelSecondsChange() > 0 && difference.visitingMinutesChange() >= 0;
            boolean secondBetter = difference.totalTravelSecondsChange() < 0 && difference.visitingMinutesChange() <= 0;
            if (firstBetter || secondBetter) return "For less travel, choose " + (firstBetter ? firstName : secondName)
                    + ": about " + Math.abs(difference.totalTravelSecondsChange()) / 60 + " minutes less travel, with no less visiting time. Your interest in the other destination may outweigh convenience.";
        }
        return tradeOff(firstName, secondName, difference);
    }
    private static Set<String> codes(Facts facts) { return facts.reasons().stream().map(VisitFeasibility.Reason::code).collect(Collectors.toSet()); }
    private static Long instantChange(Instant first, Instant second) { return first == null || second == null ? null : Duration.between(first, second).getSeconds(); }
    private static Long change(Long first, Long second) { return first == null || second == null ? null : second - first; }
    private static Long seconds(RouteEstimate route) {
        if (route == null || route.departureAt() == null || route.arrivalAt() == null || route.arrivalAt().isBefore(route.departureAt())) return null;
        return Duration.between(route.departureAt(), route.arrivalAt()).getSeconds();
    }
}
