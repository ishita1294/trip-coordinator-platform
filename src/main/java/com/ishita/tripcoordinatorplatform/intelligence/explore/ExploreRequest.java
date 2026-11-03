package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.location.LocationObservation;
import com.ishita.tripcoordinatorplatform.intelligence.routing.TravelMode;

/** userId selects a trip traveler, never the authenticated actor or capacity identity. */
public record ExploreRequest(Long userId, LocationObservation location, TravelMode mode,
        ExplorePreference preference, Integer minimumVisitMinutes, String scheduleTimeZone,
        CommitmentOverride commitmentOverride, java.util.List<TimeZoneClarification> timeZoneClarifications, java.util.List<String> evaluatedPlaceIds, ExploreIntent intent, com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity, java.time.Instant departureAt, Workflow workflow, Continuation continuation, StartContext startContext, TravelMode repositioningMode, TravelMode onwardMode, String originPlaceId) {
    /** Use either a fresh observation or an identity resolved only during explicit planning. */
    public ExploreRequest(Long userId,LocationObservation location,TravelMode mode,ExplorePreference preference,Integer minimumVisitMinutes,String scheduleTimeZone,CommitmentOverride commitmentOverride,java.util.List<TimeZoneClarification> timeZoneClarifications,java.util.List<String> evaluatedPlaceIds,ExploreIntent intent,com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity,java.time.Instant departureAt,Workflow workflow,Continuation continuation,StartContext startContext,TravelMode repositioningMode,TravelMode onwardMode) {
        this(userId,location,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,startContext,repositioningMode,onwardMode,null);
    }
    ExploreRequest withResolvedOrigin(LocationObservation observation) {
        return new ExploreRequest(userId,observation,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,startContext,repositioningMode,onwardMode,null);
    }
    /** Optional overrides apply to booking-endpoint repositioning and direct onward commitment travel only. */
    public ExploreRequest(Long userId,LocationObservation location,TravelMode mode,ExplorePreference preference,Integer minimumVisitMinutes,String scheduleTimeZone,CommitmentOverride commitmentOverride,java.util.List<TimeZoneClarification> timeZoneClarifications,java.util.List<String> evaluatedPlaceIds,ExploreIntent intent,com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity,java.time.Instant departureAt,Workflow workflow,Continuation continuation,StartContext startContext) {
        this(userId,location,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,startContext,null,null);
    }
    public record JourneyModes(TravelMode repositioning,TravelMode exploration,TravelMode onward) {}
    public TravelMode effectiveRepositioningMode(TravelMode main) {return repositioningMode==null?main:repositioningMode;}
    public TravelMode effectiveOnwardMode(TravelMode main) {return onwardMode==null?main:onwardMode;}
    public JourneyModes journeyModes() {return new JourneyModes(effectiveRepositioningMode(mode),mode,effectiveOnwardMode(mode));}
    public ExploreRequest withModeOverrides(TravelMode repositioning,TravelMode onward) {
        return new ExploreRequest(userId,location,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,startContext,repositioning,onward,originPlaceId);
    }
    public record StartContext(Long precedingItemId, LocationObservation endingLocation, Integer delayMinutes, java.time.Instant requestedAt, EndpointReference endpointReference) {
        public StartContext(Long precedingItemId, LocationObservation endingLocation, Integer delayMinutes, java.time.Instant requestedAt) {this(precedingItemId,endingLocation,delayMinutes,requestedAt,null);}
        public StartContext(Long precedingItemId, LocationObservation endingLocation, Integer delayMinutes) {this(precedingItemId,endingLocation,delayMinutes,null,null);}
    }
    public record EndpointReference(Long reservationId, String placeId, long version) {}
    public ExploreRequest(Long userId, LocationObservation location, TravelMode mode, ExplorePreference preference, Integer minimumVisitMinutes, String scheduleTimeZone, CommitmentOverride commitmentOverride, java.util.List<TimeZoneClarification> timeZoneClarifications, java.util.List<String> evaluatedPlaceIds, ExploreIntent intent, com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity, java.time.Instant departureAt, Workflow workflow, Continuation continuation) {
        this(userId,location,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,null);
    }
    public ExploreRequest withRequestedStart(java.time.Instant instant) {
        var context=startContext==null?new StartContext(null,null,0,instant):new StartContext(startContext.precedingItemId(),startContext.endingLocation(),startContext.delayMinutes(),instant,startContext.endpointReference());
        return new ExploreRequest(userId,location,mode,preference,minimumVisitMinutes,scheduleTimeZone,commitmentOverride,timeZoneClarifications,evaluatedPlaceIds,intent,opportunity,departureAt,workflow,continuation,context,repositioningMode,onwardMode,originPlaceId);
    }
    public record Continuation(java.util.List<String> pendingPlaceIds, int radiusMeters, boolean widened, String scheduleRevision,String windowContext,JourneyModes journeyModes) {
        public Continuation(java.util.List<String> ids,int radius,boolean widened,String revision,String context){this(ids,radius,widened,revision,context,null);}
        public Continuation(java.util.List<String> ids,int radius,boolean widened,String revision){this(ids,radius,widened,revision,null);}
    }
    public String windowContext() {return opportunity==null?null:opportunity.id();}
    public ExploreRequest(Long userId, LocationObservation location, TravelMode mode, ExplorePreference preference, Integer minimumVisitMinutes, String scheduleTimeZone, CommitmentOverride commitmentOverride, java.util.List<TimeZoneClarification> timeZoneClarifications, java.util.List<String> evaluatedPlaceIds, ExploreIntent intent, com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity, java.time.Instant departureAt, Workflow workflow) {
        this(userId, location, mode, preference, minimumVisitMinutes, scheduleTimeZone, commitmentOverride, timeZoneClarifications, evaluatedPlaceIds, intent, opportunity, departureAt, workflow, null);
    }
    public enum Workflow { AUTO, COMMITMENT_AWARE, OPEN_ENDED }
    public ExploreRequest(Long userId, LocationObservation location, TravelMode mode, ExplorePreference preference, Integer minimumVisitMinutes, String scheduleTimeZone, CommitmentOverride commitmentOverride, java.util.List<TimeZoneClarification> timeZoneClarifications, java.util.List<String> evaluatedPlaceIds, ExploreIntent intent, com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Selection opportunity, java.time.Instant departureAt) {
        this(userId, location, mode, preference, minimumVisitMinutes, scheduleTimeZone, commitmentOverride, timeZoneClarifications, evaluatedPlaceIds, intent, opportunity, departureAt, null);
    }
    public ExploreRequest(Long userId, LocationObservation location, TravelMode mode, ExplorePreference preference, Integer minimumVisitMinutes, String scheduleTimeZone, CommitmentOverride commitmentOverride, java.util.List<TimeZoneClarification> timeZoneClarifications, java.util.List<String> evaluatedPlaceIds, ExploreIntent intent) {
        this(userId, location, mode, preference, minimumVisitMinutes, scheduleTimeZone, commitmentOverride, timeZoneClarifications, evaluatedPlaceIds, intent, null, null);
    }
    public record TimeZoneClarification(Long itineraryItemId, String timeZone) {}
    // Explicit traveler context only; never writes authoritative booking/itinerary records.
    public record CommitmentOverride(Long itineraryItemId, Coordinates destination,
                                     String timeZone, Integer arrivalLeadMinutes, String placeId) {}
}
