package com.ishita.tripcoordinatorplatform.controller;

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

@RestController
public class ItineraryConflictController {

    private final ItineraryConflictService itineraryConflictService;
    private final ConflictResolutionService conflictResolutionService;

    public ItineraryConflictController(
            ItineraryConflictService itineraryConflictService,
            ConflictResolutionService conflictResolutionService
    ) {
        this.itineraryConflictService = itineraryConflictService;
        this.conflictResolutionService = conflictResolutionService;
    }

    @GetMapping("/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts")
    public List<ItineraryConflictResponse> getConflictsForMember(
            @PathVariable Long tripId,
            @PathVariable Long tripMemberId
    ) {
        return itineraryConflictService
                .getConflictsForMember(tripId, tripMemberId);
    }

    @PostMapping(
            "/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts/{conflictId}/suggestions"
    )
    public ConflictResolutionResponse suggestResolutions(
            @PathVariable Long tripId,
            @PathVariable Long tripMemberId,
            @PathVariable Long conflictId
    ) {
        return conflictResolutionService.suggestResolutions(
                tripId,
                tripMemberId,
                conflictId
        );
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
        return conflictResolutionService.applyConflictResolution(
                tripId,
                tripMemberId,
                conflictId,
                request
        );
    }

}
