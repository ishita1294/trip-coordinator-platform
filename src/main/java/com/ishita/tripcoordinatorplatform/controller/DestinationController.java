package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.Destination;
import com.ishita.tripcoordinatorplatform.service.DestinationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DestinationController {

    private final DestinationService destinationService;

    public DestinationController(DestinationService destinationService) {
        this.destinationService = destinationService;
    }

    @PostMapping("/trips/{tripId}/destinations")
    public Destination createDestination(
            @PathVariable Long tripId,
            @Valid @RequestBody Destination destination
    ) {
        return destinationService.createDestination(tripId, destination);
    }

    @GetMapping("/trips/{tripId}/destinations")
    public List<Destination> getDestinations(@PathVariable Long tripId) {
        return destinationService.getDestinations(tripId);
    }

    @PutMapping("/trips/{tripId}/destinations/{destinationId}")
    public Destination updateDestination(
            @PathVariable Long tripId,
            @PathVariable Long destinationId,
            @Valid @RequestBody Destination updatedDestination
    ) {
        return destinationService.updateDestination(
                tripId,
                destinationId,
                updatedDestination
        );
    }

    @DeleteMapping("/trips/{tripId}/destinations/{destinationId}")
    public void deleteDestination(
            @PathVariable Long tripId,
            @PathVariable Long destinationId
    ) {
        destinationService.deleteDestination(tripId, destinationId);
    }
}
