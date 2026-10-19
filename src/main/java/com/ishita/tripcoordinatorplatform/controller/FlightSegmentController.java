package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.FlightSegment;
import com.ishita.tripcoordinatorplatform.service.FlightSegmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/trips/{tripId}/reservations/{reservationId}/flight-segments")
public class FlightSegmentController {

    private final CoreTripOperations authorization;
    private final FlightSegmentService flightSegmentService;

    public FlightSegmentController(FlightSegmentService flightSegmentService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.flightSegmentService = flightSegmentService;
    }

    @PostMapping
    public FlightSegment createFlightSegment(
        @PathVariable Long tripId,
        @PathVariable Long reservationId,
        @RequestBody FlightSegment segment
    ) {
        return authorization.write(tripId, () -> {
            authorization.reservation(tripId, reservationId);
            authorization.newEntity(segment.getId());
            return flightSegmentService.createFlightSegment(
                tripId,
                reservationId,
                segment
            );
        });
    }

    @GetMapping
    public List<FlightSegment> getFlightSegments(
        @PathVariable Long tripId,
        @PathVariable Long reservationId
    ) {
        return authorization.read(tripId, () -> {
            authorization.reservation(tripId, reservationId);
            return flightSegmentService.getFlightSegments(
                tripId,
                reservationId
            );
        });
    }
}
