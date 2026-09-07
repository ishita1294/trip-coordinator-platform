package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.ItineraryConflict;
import com.ishita.tripcoordinatorplatform.response.ItineraryConflictResponse;
import com.ishita.tripcoordinatorplatform.service.ItineraryConflictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ItineraryConflictController {

    private final ItineraryConflictService itineraryConflictService;

    public ItineraryConflictController(
            ItineraryConflictService itineraryConflictService
    ) {
        this.itineraryConflictService = itineraryConflictService;
    }

    @GetMapping("/trips/{tripId}/members/{tripMemberId}/itinerary/conflicts")
    public List<ItineraryConflictResponse> getConflictsForMember(
            @PathVariable Long tripId,
            @PathVariable Long tripMemberId
    ) {
        return itineraryConflictService
                .getConflictsForMember(tripId, tripMemberId);
    }
}
