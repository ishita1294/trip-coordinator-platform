package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.service.TripService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @GetMapping("/")
    public String home() {
        return "Trip coordination platform is running";
    }

    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    public Trip createTrip(
            @Valid @RequestBody Trip trip,
            @RequestParam Long userId
    ) {
        return tripService.createTrip(trip, userId);
    }

    @GetMapping("/trips")
    public List<Trip> getAllTrips() {
        return tripService.getAllTrips();
    }

    @GetMapping("/trips/{id}")
    public Trip getTripById(@PathVariable Long id) {
        return tripService.getTripById(id);
    }

    @PutMapping("/trips/{id}")
    public Trip updateTrip(
            @PathVariable Long id,
            @Valid @RequestBody Trip updatedTrip
    ) {
        return tripService.updateTrip(id, updatedTrip);
    }

    @DeleteMapping("/trips/{id}")
    public void deleteTrip(@PathVariable Long id) {
        tripService.deleteTrip(id);
    }
}
