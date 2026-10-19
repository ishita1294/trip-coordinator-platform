package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;


import com.ishita.tripcoordinatorplatform.model.ItineraryItemParticipant;
import com.ishita.tripcoordinatorplatform.service.ItineraryItemParticipantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ItineraryItemParticipantController {

    private final CoreTripOperations authorization;
    private final ItineraryItemParticipantService participantService;

    public ItineraryItemParticipantController(
        ItineraryItemParticipantService participantService,
        CoreTripOperations authorization
    ) {
        this.authorization = authorization;
        this.participantService = participantService;
    }

    @PostMapping(
        "/trips/{tripId}/itinerary/items/{itemId}/participants/{tripMemberId}"
    )
    public ItineraryItemParticipant addParticipant(
        @PathVariable Long tripId,
        @PathVariable Long itemId,
        @PathVariable Long tripMemberId
    ) {
        return authorization.write(tripId, () -> {
            authorization.item(tripId, itemId);
            authorization.member(tripId, tripMemberId);
            return participantService.addParticipant(
                tripId,
                itemId,
                tripMemberId
            );
        });
    }

    @GetMapping("/trips/{tripId}/itinerary/items/{itemId}/participants")
    public List<ItineraryItemParticipant> getParticipants(
        @PathVariable Long tripId,
        @PathVariable Long itemId
    ) {
        return authorization.read(tripId, () -> {
            authorization.item(tripId, itemId);
            return participantService.getParticipants(tripId, itemId);
        });
    }

}
