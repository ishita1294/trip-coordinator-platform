package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.model.Trip;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.List;

/** Manages trips for the signed-in user. Trip ownership comes from authentication, never a caller-supplied user ID. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class TripController {
    private final AuthenticatedTripService trips;
    private final CurrentUser current;
    private final CoreTripOperations authorization;
    public TripController(AuthenticatedTripService trips, CurrentUser current, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.trips = trips;
        this.current = current;
    }
    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    public Trip createTrip(@Valid @RequestBody AuthenticatedTripService.CreatePersonalTrip request,
                          @RequestParam(required=false) Long userId) {
        if (userId != null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Owner userId is not accepted; ownership comes from authentication");
        // Resolve or create the local user before starting the trip-creation transaction.
        return trips.createForCurrent(request, current.require().getId());
    }
    @GetMapping("/trips")
    public List<Trip> getAllTrips(@RequestParam(required=false) Long userId) {
        if (userId != null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                "User filters are not accepted; trips belong to the authenticated actor");
        return trips.list();
    }
    @GetMapping("/trips/{id}")
    public Trip getTripById(@PathVariable Long id) { return trips.read(id); }

    @PutMapping("/trips/{id}")
    public Trip updateTrip(@PathVariable Long id, @Valid @RequestBody AuthenticatedTripService.CreatePersonalTrip request) {
        return authorization.write(id, () -> trips.updatePlanningFields(id,request));
    }
    @DeleteMapping("/trips/{id}")
    public void deleteTrip(@PathVariable Long id) {
        authorization.ownerWrite(id, () -> { trips.deleteEmptyTrip(id);return null; });
    }
}
