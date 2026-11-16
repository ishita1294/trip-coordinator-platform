package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate;
import com.ishita.tripcoordinatorplatform.intelligence.routing.RouteEstimate;
import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility.Reason;
import java.util.List;

/** Discovery suitability, deliberately not a claim that a place fits an unbounded schedule. */
public record OpenEndedAssessment(PlaceCandidate place, Suitability suitability, RouteEstimate outbound,
                                  String openingHoursEvidence, List<Reason> reasons, String explanation) {
    public enum Suitability { SUITABLE, VERIFY_DETAILS, UNAVAILABLE }
    public OpenEndedAssessment explained(String value) {
        return new OpenEndedAssessment(place, suitability, outbound, openingHoursEvidence, reasons, value);
    }
}
