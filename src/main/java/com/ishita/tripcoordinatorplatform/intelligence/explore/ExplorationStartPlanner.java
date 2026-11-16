package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.location.*;
import com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities;
import com.ishita.tripcoordinatorplatform.intelligence.routing.*;
import java.time.*;
import java.util.*;

/** Derived timing only. Callers reserve/authorize the existing routing operation; no provider data is cached. */
public final class ExplorationStartPlanner {
    public record Plan(Instant departureAt,Instant arrivalAt,Instant explorationAt,RouteEstimate repositioning,
                       int delayMinutes,String status,List<String> warnings) {
        @com.fasterxml.jackson.annotation.JsonProperty public Instant earliestAt() {return arrivalAt==null?null:arrivalAt.plusSeconds(delayMinutes*60L);}
    }
    private ExplorationStartPlanner() {}
    public static Plan calculate(ExploreRequest request,FreeTimeOpportunities.Opportunity window,TravelMode mode,
                                 Instant requestedStart,Instant now,Instant arrivalDeadline,FeasibilityEngine feasibility) {
        return calculate(request,window,mode,requestedStart,now,arrivalDeadline,(from,to,travelMode,departure,arrival)->feasibility.directRoute(from,to,travelMode,departure));
    }
    public static Plan calculate(ExploreRequest request,FreeTimeOpportunities.Opportunity window,TravelMode mode,
                                 Instant requestedStart,Instant now,Instant arrivalDeadline,RoutingProvider routes) {
        mode=request.effectiveRepositioningMode(mode);
        var context=request.startContext();int delay=context==null || context.delayMinutes()==null?0:context.delayMinutes();
        validateContext(request,window);
        Instant boundary=window==null?now:window.earliestStart();
        Instant leave=boundary.isAfter(now)?boundary:now;
        LocationObservation ending=context==null?null:context.endingLocation();
        Coordinates from=null;
        boolean underway=isUnderway(window,now);
        if(request.location().source()==LocationObservation.Source.DEVICE && (!fresh(request.location(),now) || leave.isAfter(now.plusSeconds(300))))
            return unknown(delay,"Confirm your planned starting point; a future or stale device position is not departure evidence.");
        if(underway) {
            if(request.location().source()==LocationObservation.Source.DEVICE && fresh(request.location(),now)) from=request.location().coordinates();
            else if(ending!=null && ending.source()==LocationObservation.Source.DEVICE && fresh(ending,now)) from=ending.coordinates();
            else return unknown(delay,"This window is underway. Refresh your current location before calculating travel to a different starting point.");
        } else if(window!=null && window.before()!=null) {
            var persisted=window.before().endingEvidence();
            if(persisted!=null && persisted.reservationId()!=null) {
                if(!persisted.confirmed())return unknown(delay,"Confirm and save where the preceding activity ends before calculating exploration travel.");
                if(persisted.coordinates()==null)return unknown(delay,"The activity ending location is saved, but its geographic location must be resolved before calculating exploration travel.");
                // Server evidence is authoritative; client coordinates are not an endpoint identity.
                from=persisted.coordinates();
            }
            if(from==null && ending!=null) {
                if(context.precedingItemId()==null || !fresh(ending,now) || ending.source()==LocationObservation.Source.DEVICE && leave.isAfter(now.plusSeconds(300))) return unknown(delay,"Confirm the preceding activity’s ending location before calculating your start.");
                from=ending.coordinates();
            } else if(from==null && !window.before().flight()) {
                from=window.before().coordinates();
                if(window.before().originRequiresConfirmation() && from!=null && !from.equals(request.location().coordinates()))
                    return unknown(delay,"Confirm where the preceding activity ends before choosing a different starting point.");
            }
            if(from==null) return unknown(delay,"The preceding activity’s ending location is unresolved; no travel or start time was invented.");
        } else from=request.location().coordinates();
        RouteEstimate reposition=null;Instant arrival=leave;
        if(!from.equals(request.location().coordinates())) {
            try {
                var route=routes.route(from,request.location().coordinates(),mode,leave,null);
                if(route.isEmpty()) return unknown(delay,"No route to the selected starting point is available in this travel mode.");
                reposition=route.get();
                if(reposition.departureAt().isBefore(leave) || reposition.arrivalAt().isBefore(reposition.departureAt()) || !reposition.warnings().isEmpty())
                    return unknown(delay,"Travel to the starting point has unresolved timing or route warnings.");
                arrival=reposition.arrivalAt();
            } catch(ProviderUnavailableException failure) {throw failure;}
        }
        Instant explore=arrival.plusSeconds(delay*60L);
        Instant explicit=context==null?null:context.requestedAt();
        if(explicit!=null && explicit.isBefore(explore)) return new Plan(leave,arrival,explicit,reposition,delay,"INFEASIBLE",List.of("Your requested start is earlier than you can reach the starting point. Choose a time at or after the earliest feasible start."));
        if(explicit!=null) explore=explicit;
        else if(requestedStart!=null && requestedStart.isAfter(explore)) explore=requestedStart;
        boolean fits=arrivalDeadline==null || explore.isBefore(arrivalDeadline);
        return new Plan(leave,arrival,explore,reposition,delay,fits?"READY":"INFEASIBLE",fits?List.of():List.of("Travel to the starting point and the delay leave no time before the required arrival deadline."));
    }
    static void validateContext(ExploreRequest request,FreeTimeOpportunities.Opportunity window) {
        var context=request.startContext();
        if(context==null)return;
        if(context.delayMinutes()!=null && (context.delayMinutes()<0 || context.delayMinutes()>1440))throw new IllegalArgumentException("Start delay must be 0–1440 minutes.");
        if(context.precedingItemId()!=null && (window==null || window.before()==null || !context.precedingItemId().equals(window.before().itineraryItemId())))
            throw new IllegalArgumentException("Ending location must belong to this window’s preceding activity.");
        var reference=context.endpointReference();
        if(reference!=null) {
            var saved=window==null || window.before()==null?null:window.before().endingEvidence();
            if(saved==null || !Objects.equals(reference.reservationId(),saved.reservationId()) || !Objects.equals(reference.placeId(),saved.placeId()) || reference.version()!=saved.version())
                throw new IllegalArgumentException("The confirmed activity endpoint changed. Refresh the selected window.");
        }
        // Legacy requests are fenced by the selected opportunity revision, which includes endpoint identity/version.
    }
    private static boolean isUnderway(FreeTimeOpportunities.Opportunity window,Instant now) {
        return window!=null && window.before()!=null && !window.earliestStart().isAfter(now.plusSeconds(5))
                && window.before().end()!=null && window.before().end().isBefore(now.minusSeconds(5));
    }
    static boolean hasTrustedUnderwayOrigin(ExploreRequest request,FreeTimeOpportunities.Opportunity window,Instant now) {
        if(!isUnderway(window,now))return false;
        if(request.location().source()==LocationObservation.Source.DEVICE)return fresh(request.location(),now);
        var ending=request.startContext()==null?null:request.startContext().endingLocation();
        return ending!=null && ending.source()==LocationObservation.Source.DEVICE && fresh(ending,now);
    }
    public static void requireReady(Plan plan) {
        if(plan.status().equals("INFEASIBLE")) throw new IllegalArgumentException(plan.warnings().getFirst());
        if(!plan.status().equals("READY")) throw new ProviderUnavailableException(plan.warnings().getFirst());
    }
    private static boolean fresh(LocationObservation observation,Instant now) {
        return !observation.observedAt().isBefore(now.minusSeconds(300)) && !observation.observedAt().isAfter(now.plusSeconds(60))
                && (observation.accuracyMeters()==null || observation.accuracyMeters()<=500)
                && (observation.source()!=LocationObservation.Source.DEVICE || observation.accuracyMeters()!=null);
    }
    private static Plan unknown(int delay,String reason) {return new Plan(null,null,null,null,delay,"UNVERIFIED",List.of(reason));}
}
