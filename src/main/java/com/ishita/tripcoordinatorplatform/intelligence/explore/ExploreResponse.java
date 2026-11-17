package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.commitment.Commitment;
import com.ishita.tripcoordinatorplatform.intelligence.location.LocationObservation;
import java.time.Instant;
import java.util.List;

public record ExploreResponse(Instant assessedAt, Instant expiresAt, LocationObservation location,
        boolean locationFresh, Commitment nextCommitment, String contextStatus, String rankingStatus,
        List<String> warnings, List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> feasible,
        List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> conditional, int excludedCount,
        List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> destinationChoices, Discovery discovery, List<OpenEndedAssessment> openEnded, ExplorationStartPlanner.Plan explorationStart, ExploreRequest.JourneyModes journeyModes) {
    public ExploreResponse(Instant assessedAt,Instant expiresAt,LocationObservation location,boolean locationFresh,Commitment nextCommitment,String contextStatus,String rankingStatus,List<String> warnings,List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> feasible,List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> conditional,int excludedCount,List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> destinationChoices,Discovery discovery,List<OpenEndedAssessment> openEnded,ExplorationStartPlanner.Plan explorationStart) {
        this(assessedAt,expiresAt,location,locationFresh,nextCommitment,contextStatus,rankingStatus,warnings,feasible,conditional,excludedCount,destinationChoices,discovery,openEnded,explorationStart,null);
    }
    public ExploreResponse withModes(ExploreRequest.JourneyModes modes) {return new ExploreResponse(assessedAt,expiresAt,location,locationFresh,nextCommitment,contextStatus,rankingStatus,warnings,feasible,conditional,excludedCount,destinationChoices,discovery,openEnded,explorationStart,modes);}
    public ExploreResponse(Instant assessedAt,Instant expiresAt,LocationObservation location,boolean locationFresh,Commitment nextCommitment,String contextStatus,String rankingStatus,List<String> warnings,List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> feasible,List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> conditional,int excludedCount,List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> destinationChoices,Discovery discovery,List<OpenEndedAssessment> openEnded) {
        this(assessedAt,expiresAt,location,locationFresh,nextCommitment,contextStatus,rankingStatus,warnings,feasible,conditional,excludedCount,destinationChoices,discovery,openEnded,null);
    }
    public ExploreResponse withStart(ExplorationStartPlanner.Plan plan) {return new ExploreResponse(assessedAt,expiresAt,location,locationFresh,nextCommitment,contextStatus,rankingStatus,warnings,feasible,conditional,excludedCount,destinationChoices,discovery,openEnded,plan,journeyModes);}

    public ExploreResponse(Instant assessedAt, Instant expiresAt, LocationObservation location, boolean locationFresh, Commitment nextCommitment, String contextStatus, String rankingStatus, List<String> warnings, List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> feasible, List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> conditional, int excludedCount, List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> destinationChoices, Discovery discovery) {
        this(assessedAt, expiresAt, location, locationFresh, nextCommitment, contextStatus, rankingStatus, warnings, feasible, conditional, excludedCount, destinationChoices, discovery, List.of());
    }
    public record Discovery(int radiusMeters, int discovered, java.util.List<String> evaluatedPlaceIds, int remaining, boolean widerSearchAvailable, int widerRadiusMeters, List<CandidateDecision> decisions, ExploreRequest.Continuation continuation, String progressStatus, com.ishita.tripcoordinatorplatform.intelligence.google.GoogleRequestBudget.Usage usage) {
        public Discovery(int radiusMeters, int discovered, List<String> evaluatedPlaceIds, int remaining, boolean widerSearchAvailable, int widerRadiusMeters, List<CandidateDecision> decisions) {
            this(radiusMeters,discovered,evaluatedPlaceIds,remaining,widerSearchAvailable,widerRadiusMeters,decisions,null,null,null);
        }
    }
    public record CandidateDecision(String id, String name, String primaryType, double selectionScore, String stage, List<String> reasons, List<String> types) {
        public CandidateDecision(String id, String name, String primaryType, double selectionScore, String stage, List<String> reasons) {
            this(id,name,primaryType,selectionScore,stage,reasons,List.of());
        }
    }
    public ExploreResponse(Instant assessedAt, Instant expiresAt, LocationObservation location, boolean locationFresh, Commitment nextCommitment, String contextStatus, String rankingStatus, List<String> warnings, List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> feasible, List<com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility> conditional, int excludedCount, List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> destinationChoices) {
        this(assessedAt, expiresAt, location, locationFresh, nextCommitment, contextStatus, rankingStatus, warnings, feasible, conditional, excludedCount, destinationChoices, null);
    }
}
