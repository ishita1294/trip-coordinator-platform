package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.Destination;
import com.ishita.tripcoordinatorplatform.service.DestinationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class DestinationController {

    private final CoreTripOperations authorization;
    private final DestinationService destinationService;

    public DestinationController(DestinationService destinationService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.destinationService = destinationService;
    }

    @PostMapping("/trips/{tripId}/destinations")
    public Destination createDestination(
        @PathVariable Long tripId,
        @Valid @RequestBody Destination destination
    ) {
        return authorization.write(tripId, () -> {
            authorization.newEntity(destination.getId());
            return destinationService.createDestination(tripId, destination);
        });
    }

    @GetMapping("/trips/{tripId}/destinations")
    public List<Destination> getDestinations(@PathVariable Long tripId) {
        return authorization.read(tripId, () -> {
            return destinationService.getDestinations(tripId);
        });
    }

    @PutMapping("/trips/{tripId}/destinations/{destinationId}")
    public Destination updateDestination(
        @PathVariable Long tripId,
        @PathVariable Long destinationId,
        @Valid @RequestBody Destination updatedDestination
    ) {
        return authorization.write(tripId, () -> {
            authorization.destination(tripId, destinationId);
            return destinationService.updateDestination(
                tripId,
                destinationId,
                updatedDestination
            );
        });
    }

    @DeleteMapping("/trips/{tripId}/destinations/{destinationId}")
    public void deleteDestination(
        @PathVariable Long tripId,
        @PathVariable Long destinationId
    ) {
        authorization.write(tripId, () -> {
            authorization.destination(tripId, destinationId);
            destinationService.deleteDestination(tripId, destinationId);
            return null;
        });
    }
}
