package com.ishita.tripcoordinatorplatform.ai.document;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class FlightExtractionValidator {
    public void validate(FlightExtractionResult result) {

        // AI extraction must contain at least one reservation
        // before it can move forward to user review.
        if (result == null
                || result.reservations() == null
                || result.reservations().isEmpty()) {
            throw new IllegalArgumentException(
                    "Flight extraction must contain at least one reservation"
            );
        }

        for (FlightReservationExtraction reservation : result.reservations()) {
            if (reservation == null
                    || reservation.segments() == null
                    || reservation.segments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Flight reservation extraction must contain at least one segment"
                );
            }

            if ((reservation.confirmationNumber() == null)
                    != (reservation.confirmationNumberType() == null)) {
                throw new IllegalArgumentException(
                        "Confirmation number and type must both be present or both be null"
                );
            }

            if (reservation.confirmationNumber() != null
                    && reservation.confirmationNumber().isBlank()) {
                throw new IllegalArgumentException(
                        "Confirmation number must not be blank"
                );
            }

            validateSegments(reservation);
        }
    }

    private void validateSegments(FlightReservationExtraction reservation) {
        Set<Integer> seenSegmentOrders = new HashSet<>();

        for (FlightSegmentExtraction segment : reservation.segments()) {

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
