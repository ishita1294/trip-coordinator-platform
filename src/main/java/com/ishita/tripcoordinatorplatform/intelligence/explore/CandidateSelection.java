package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.places.*;
import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility;
import com.ishita.tripcoordinatorplatform.intelligence.routing.RouteEstimate;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Activity admission, reputation and practical ordering are distinct from schedule feasibility. */
final class CandidateSelection {
    private CandidateSelection() {}
    static List<PlaceCandidate> order(List<PlaceCandidate> places, Coordinates origin) { return order(places,origin,null); }
    static List<PlaceCandidate> order(List<PlaceCandidate> places, Coordinates origin, Instant departure) {
        Map<String,PlaceCandidate> unique = new LinkedHashMap<>();
        for (var p : places) if (p.id()!=null && !p.id().isBlank() && p.name()!=null && !p.name().isBlank()) unique.putIfAbsent(p.id(),p);
        return diverse(new ArrayList<>(unique.values()), p -> score(p,origin) + openingHint(p,departure), ActivityRelevance::family,0);
    }
    static boolean relevant(PlaceCandidate place, ExplorePreference preference, ExploreIntent intent) {
        return rejection(place,preference,intent)==null;
    }
    static String rejection(PlaceCandidate place, ExplorePreference preference, ExploreIntent intent) {
        String reason = ActivityRelevance.rejection(place);
        if (reason!=null) return reason;
        if (preference == ExplorePreference.ENTERTAINMENT && !ActivityRelevance.entertainmentVenue(place))
            return "ENTERTAINMENT_VENUE_UNVERIFIED";
        return (preference!=ExplorePreference.ANYTHING && !place.interests().contains(preference)) || (intent!=null && !place.intents().contains(intent))
                ? "PREFERENCE_CATEGORY_MISMATCH" : null;
    }
    static List<OpenEndedAssessment> orderOpenEnded(List<OpenEndedAssessment> values) { return orderOpenEnded(values,0); }
    static List<OpenEndedAssessment> orderOpenEnded(List<OpenEndedAssessment> values, double aiTieTolerance) {
        return diverse(values, c -> openScore(c), c -> ActivityRelevance.family(c.place()),aiTieTolerance);
    }
    /** Prove that every diversity-adjusted choice has exactly one eligible candidate.
     * If so, no permutation from the model can alter the existing guarded order. */
    static boolean openOrderNeedsAi(List<OpenEndedAssessment> values) {
        var remaining = new ArrayList<>(values);
        Map<String,Integer> counts = new HashMap<>();
        while (!remaining.isEmpty()) {
            ToDoubleFunction<OpenEndedAssessment> score = c -> openScore(c)
                    - 1.05 * counts.getOrDefault(ActivityRelevance.family(c.place()), 0);
            double best = remaining.stream().mapToDouble(score).max().orElseThrow();
            var eligible = remaining.stream().filter(c -> score.applyAsDouble(c) >= best - .2).toList();
            if (eligible.size() > 1) return true;
            var choice = eligible.getFirst();
            remaining.remove(choice);
            counts.merge(ActivityRelevance.family(choice.place()), 1, Integer::sum);
        }
        return false;
    }
    static double openScore(OpenEndedAssessment c) {
        return -10*evidenceTier(c) + quality(c.place()) - effort(c.outbound(),0);
    }
    static List<VisitFeasibility> orderBounded(List<VisitFeasibility> values, double aiTieTolerance) {
        return diverse(values,c -> boundedScore(c),c -> ActivityRelevance.family(c.place()),aiTieTolerance);
    }
    static double boundedScore(VisitFeasibility c) {
        double onward = c.returnPlan()==null ? 0 : minutes(c.returnPlan().route());
        return (c.status()==VisitFeasibility.Status.FEASIBLE ? 0 : -10) + quality(c.place())
                - (c.outbound()==null || c.returnPlan()==null ? 4 : effort(c.outbound(),onward));
    }
    private static <T> List<T> diverse(List<T> values, ToDoubleFunction<T> score, Function<T,String> family, double tolerance) {
        List<T> remaining=new ArrayList<>(values),ordered=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        while(!remaining.isEmpty()) {
            ToDoubleFunction<T> adjusted = p -> score.applyAsDouble(p)-1.05*counts.getOrDefault(family.apply(p),0);
            double best=remaining.stream().mapToDouble(adjusted).max().orElseThrow();
            // AI can choose among similarly practical alternatives, not override clear evidence advantages.
            T choice=remaining.stream().filter(p -> adjusted.applyAsDouble(p)>=best-tolerance).findFirst().orElseThrow();
            ordered.add(choice); remaining.remove(choice);counts.merge(family.apply(choice),1,Integer::sum);
        }
        return List.copyOf(ordered);
    }
    static int evidenceTier(OpenEndedAssessment value) {
        if(value.outbound()==null) return 3;
        if(value.reasons().stream().anyMatch(r -> r.code().equals("LATE_NIGHT_OPENING_UNVERIFIED"))) return 2;
        return value.openingHoursEvidence().equals("PUBLISHED_OPEN_AT_ARRIVAL") ? 0 : 1;
    }
    private static double quality(PlaceCandidate p) {
        if(ActivityRelevance.rejection(p)!=null) return -100;
        int reviews=p.reviewCount()==null ? 0 : Math.max(0,p.reviewCount());
        double reputation=((p.rating()==null ? 3.5 : p.rating())*reviews+3.5*30)/(reviews+30);
        // Review volume strengthens confidence, not an unlimited claim of visitor value.
        return reputation+.25*Math.log10(1+Math.min(reviews,2000));
    }
    static double score(PlaceCandidate place, Coordinates origin) {
        return quality(place)-.3*Math.log1p(origin.distanceMetersTo(place.coordinates())/1000);
    }
    private static double openingHint(PlaceCandidate p,Instant departure) {
        if(departure==null) return 0;
        if(p.hours()!=null && !departure.isBefore(p.hours().coverageStart()) && departure.isBefore(p.hours().coverageEnd()))
            return p.hours().intervals().stream().anyMatch(i -> !departure.isBefore(i.opensAt()) && departure.isBefore(i.closesAt())) ? .5 : -.25;
        if(p.timeZone()!=null) { int hour=departure.atZone(ZoneId.of(p.timeZone())).getHour(); if(hour<6) return -.5; }
        return 0;
    }
    static double minutes(RouteEstimate route) { return Duration.between(route.departureAt(),route.arrivalAt()).toSeconds()/60.0; }
    private static double effort(RouteEstimate outbound,double onward) {
        if(outbound==null) return 4;
        double weight=switch(outbound.mode()) { case WALK -> .045; case DRIVE -> .025; case TRANSIT -> .035; };
        // Soft cost, not a mandatory distance cutoff. Stronger activities can justify longer journeys.
        return Math.min(4,weight*(minutes(outbound)+onward));
    }
}
