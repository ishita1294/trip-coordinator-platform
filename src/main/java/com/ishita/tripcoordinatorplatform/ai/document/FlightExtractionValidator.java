package com.ishita.tripcoordinatorplatform.ai.document;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class FlightExtractionValidator {
    public void validate(FlightExtractionResult result) {

        // AI extraction must contain at least one flight segment
        // before it can move forward to user review.
        if (result == null
                || result.segments() == null
                || result.segments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Flight extraction must contain at least one segment"
            );
        }

        Set<Integer> seenSegmentOrders = new HashSet<>();

        for (FlightSegmentExtraction segment : result.segments()) {

            if (segment.segmentOrder() == null
                    || segment.segmentOrder() < 1) {
                throw new IllegalArgumentException(
                        "Flight segment order must be greater than zero"
                );
            }

            // Each segment order must identify one unique leg within the booking.
            if (!seenSegmentOrders.add(segment.segmentOrder())) {
                throw new IllegalArgumentException(
                        "Duplicate flight segment order"
                );
            }

            if (segment.flightNumber() == null
                    || segment.flightNumber().isBlank()) {
                throw new IllegalArgumentException(
                        "Flight number is required"
                );
            }

            if (!isValidAirportCode(segment.departureAirportCode())
                    || !isValidAirportCode(segment.arrivalAirportCode())) {
                throw new IllegalArgumentException(
                        "Departure and arrival airport codes must be 3 letters"
                );
            }

            if (segment.departureDateTime() == null
                    || segment.arrivalDateTime() == null) {
                throw new IllegalArgumentException(
                        "Departure and arrival times are required"
                );
            }
        }
    }

    private boolean isValidAirportCode(String airportCode) {

        // This only validates the shape of an IATA-style code.
        // Whether the airport actually exists will be verified later
        // against trusted airport data.
        return airportCode != null
                && airportCode.matches("[A-Za-z]{3}");
    }
}
