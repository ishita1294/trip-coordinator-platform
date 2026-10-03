package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.FlightSegment;
import com.ishita.tripcoordinatorplatform.service.FlightSegmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/trips/{tripId}/reservations/{reservationId}/flight-segments")
public class FlightSegmentController {

    private final FlightSegmentService flightSegmentService;

    public FlightSegmentController(FlightSegmentService flightSegmentService) {
        this.flightSegmentService = flightSegmentService;
    }

    @PostMapping
    public FlightSegment createFlightSegment(
            @PathVariable Long tripId,
            @PathVariable Long reservationId,
            @RequestBody FlightSegment segment
    ) {
        return flightSegmentService.createFlightSegment(
                tripId,
                reservationId,
                segment
        );
    }

    @GetMapping
    public List<FlightSegment> getFlightSegments(
            @PathVariable Long tripId,
            @PathVariable Long reservationId
    ) {
        return flightSegmentService.getFlightSegments(
                tripId,
                reservationId
        );
    }
}
