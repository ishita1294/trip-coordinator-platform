package com.ishita.tripcoordinatorplatform.intelligence.planning;

import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate;
import com.ishita.tripcoordinatorplatform.intelligence.routing.DeparturePlanner.DeparturePlan;
import com.ishita.tripcoordinatorplatform.intelligence.routing.RouteEstimate;
import java.time.Instant;
import java.util.List;

// Shared derived assessment, independent of EXPLORE HTTP requests/responses.
public record VisitFeasibility(PlaceCandidate place, Status status, Instant visitStartsAt, Instant visitEndsAt,
        Long availableVisitMinutes, RouteEstimate outbound, DeparturePlan returnPlan,
        List<Reason> reasons, String explanation) {
    public enum Status { FEASIBLE, CONDITIONAL, INFEASIBLE }
    public record Reason(String code, String detail) {}
    public VisitFeasibility explained(String text) {
        return new VisitFeasibility(place, status, visitStartsAt, visitEndsAt, availableVisitMinutes,
                outbound, returnPlan, reasons, text);
    }
}
