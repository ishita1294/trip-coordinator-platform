package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.request.CreateItineraryItemRequest;
import com.ishita.tripcoordinatorplatform.service.ItineraryItemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ItineraryItemController {

    private final ItineraryItemService itineraryItemService;

    public ItineraryItemController(ItineraryItemService itineraryItemService) {
        this.itineraryItemService = itineraryItemService;
    }

    @PostMapping("/trips/{tripId}/itinerary/items")
    public ItineraryItem createItineraryItem(
            @PathVariable Long tripId,
            @Valid @RequestBody CreateItineraryItemRequest request
    ) {
        return itineraryItemService.createItineraryItem(tripId,request);
    }

    @GetMapping("/trips/{tripId}/itinerary/items")
    public List<ItineraryItem> getItineraryItems(
            @PathVariable Long tripId
    ) {
        return itineraryItemService.getItineraryItems(tripId);
    }

    @PutMapping("/trips/{tripId}/itinerary/items/{itemId}")
    public ItineraryItem updateItineraryItem(
            @PathVariable Long tripId,
            @PathVariable Long itemId,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Long reservationId,
            @Valid @RequestBody ItineraryItem updatedItem
    ) {
        return itineraryItemService.updateItineraryItem(
                tripId,
                itemId,
                activityId,
                reservationId,
                updatedItem
        );
    }

    @DeleteMapping("/trips/{tripId}/itinerary/items/{itemId}")
    public void deleteItineraryItem(
            @PathVariable Long tripId,
            @PathVariable Long itemId
    ) {
        itineraryItemService.deleteItineraryItem(tripId, itemId);
    }
}
