package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.ItineraryConflict;
import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.request.ApplyConflictResolutionRequest;
import com.ishita.tripcoordinatorplatform.response.ItineraryConflictResponse;
import com.ishita.tripcoordinatorplatform.service.ConflictResolutionService;
import com.ishita.tripcoordinatorplatform.service.ItineraryConflictService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.ishita.tripcoordinatorplatform.ai.ConflictResolutionResponse;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ItineraryConflictController {

    private final CoreTripOperations authorization;
    private final ItineraryConflictService itineraryConflictService;
    private final ConflictResolutionService conflictResolutionService;

    public ItineraryConflictController(
        ItineraryConflictService itineraryConflictService,
        ConflictResolutionService conflictResolutionService,
        CoreTripOperations authorization
    ) {
        this.authorization = authorization;
        this.itineraryConflictService = itineraryConflictService;
        this.conflictResolutionService = conflictResolutionService;
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ProblemDetail domainFailure(org.springframework.web.server.ResponseStatusException error) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason());
    }

    @GetMapping("/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts")
    public List<ItineraryConflictResponse> getConflictsForMember(
        @PathVariable Long tripId,
        @PathVariable Long tripMemberId
    ) {
        return authorization.read(tripId, () -> {
            authorization.member(tripId, tripMemberId);
            return itineraryConflictService
            .getConflictsForMember(tripId, tripMemberId);
        });
    }

    @PostMapping(
        "/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts/{conflictId}/suggestions"
    )
    public ConflictResolutionResponse suggestResolutions(
        @PathVariable Long tripId,
        @PathVariable Long tripMemberId,
        @PathVariable Long conflictId
    ) {
        return authorization.read(tripId, () -> {
            authorization.member(tripId, tripMemberId);
            authorization.conflict(tripId, tripMemberId, conflictId);
            return conflictResolutionService.suggestResolutions(
                tripId,
                tripMemberId,
                conflictId
            );
        });
    }

    @PostMapping(
        "/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts/{conflictId}/apply"
    )
    public ItineraryItem applyConflictResolution(
        @PathVariable Long tripId,
        @PathVariable Long tripMemberId,
        @PathVariable Long conflictId,
        @Valid @RequestBody ApplyConflictResolutionRequest request
    ) {
        return authorization.write(tripId, () -> {
            authorization.member(tripId, tripMemberId);
            authorization.conflict(tripId, tripMemberId, conflictId);
            authorization.item(tripId, request.getItineraryItemId());
            return conflictResolutionService.applyConflictResolution(
                tripId,
                tripMemberId,
                conflictId,
                request
            );
        });
    }

}
