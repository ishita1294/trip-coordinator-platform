package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.request.CreateItineraryItemRequest;
import com.ishita.tripcoordinatorplatform.service.ItineraryItemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ItineraryItemController {

    private final CoreTripOperations authorization;
    private final ItineraryItemService itineraryItemService;

    public ItineraryItemController(ItineraryItemService itineraryItemService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.itineraryItemService = itineraryItemService;
    }

    @PostMapping("/trips/{tripId}/itinerary/items")
    public ItineraryItem createItineraryItem(
        @PathVariable Long tripId,
        @Valid @RequestBody CreateItineraryItemRequest request
    ) {
        return authorization.write(tripId, () -> {
            authorization.activity(tripId, request.getActivityId());
            authorization.reservation(tripId, request.getReservationId());
            if (request.getParticipantIds() != null) for (Long memberId : request.getParticipantIds()) authorization.member(tripId, memberId);
            return itineraryItemService.createItineraryItem(tripId,request);
        });
    }

    @GetMapping("/trips/{tripId}/itinerary/items")
    public List<ItineraryItem> getItineraryItems(
        @PathVariable Long tripId
    ) {
        return authorization.read(tripId, () -> {
            return itineraryItemService.getItineraryItems(tripId);
        });
    }

    @PutMapping("/trips/{tripId}/itinerary/items/{itemId}")
    public ItineraryItem updateItineraryItem(
        @PathVariable Long tripId,
        @PathVariable Long itemId,
        @RequestParam(required = false) Long activityId,
        @RequestParam(required = false) Long reservationId,
        @Valid @RequestBody ItineraryItem updatedItem
    ) {
        return authorization.write(tripId, () -> {
            authorization.reservation(tripId, reservationId);
            authorization.item(tripId, itemId);
            authorization.activity(tripId, activityId);
            return itineraryItemService.updateItineraryItem(
                tripId,
                itemId,
                activityId,
                reservationId,
                updatedItem
            );
        });
    }

    @DeleteMapping("/trips/{tripId}/itinerary/items/{itemId}")
    public void deleteItineraryItem(
        @PathVariable Long tripId,
        @PathVariable Long itemId
    ) {
        authorization.write(tripId, () -> {
            authorization.item(tripId, itemId);
            itineraryItemService.deleteItineraryItem(tripId, itemId);
            return null;
        });
    }
}
