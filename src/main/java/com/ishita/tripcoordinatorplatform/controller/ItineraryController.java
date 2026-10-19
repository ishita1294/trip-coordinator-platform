package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.Itinerary;
import com.ishita.tripcoordinatorplatform.service.ItineraryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ItineraryController {

    private final CoreTripOperations authorization;
    private final ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.itineraryService = itineraryService;
    }

    @PostMapping("/trips/{tripId}/itinerary")
    public Itinerary createItinerary(@PathVariable Long tripId) {
        return authorization.write(tripId, () -> {
            return itineraryService.createItinerary(tripId);
        });
    }

    @org.springframework.web.bind.annotation.GetMapping("/trips/{tripId}/itinerary")
    public Itinerary getItinerary(@PathVariable Long tripId) {
        return authorization.read(tripId, () -> itineraryService.getItinerary(tripId));
    }
}
