package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.Itinerary;
import com.ishita.tripcoordinatorplatform.service.ItineraryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ItineraryController {

    private final ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService) {
        this.itineraryService = itineraryService;
    }

    @PostMapping("/trips/{tripId}/itinerary")
    public Itinerary createItinerary(@PathVariable Long tripId) {
        return itineraryService.createItinerary(tripId);
    }
}
