package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.ReservationConfirmationType;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightReservation;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightSegment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

@Component
public class FlightDocumentConfirmationValidator {

    public void validate(List<ReviewedFlightReservation> reservations) {
        if (reservations == null) {
            throw new IllegalArgumentException("Flight document confirmation request is required");
        }
        if (reservations.isEmpty()) {
            throw new IllegalArgumentException("Flight confirmation must contain at least one reservation");
        }

        Set<Map.Entry<String, ReservationConfirmationType>> seenIdentifiers = new HashSet<>();
        for (ReviewedFlightReservation reservation : reservations) {
            if (reservation == null) {
                throw new IllegalArgumentException("Reviewed flight reservation must not be null");
            }
            if (reservation.segments() == null || reservation.segments().isEmpty()) {
                throw new IllegalArgumentException("Reviewed flight reservation must contain at least one segment");
            }
            if (reservation.confirmationNumber() == null || reservation.confirmationNumber().isBlank()) {
                throw new IllegalArgumentException("Confirmation number is required");
            }
            if (reservation.confirmationNumberType() == null) {
                throw new IllegalArgumentException("Confirmation number type is required");
            }
            if (!seenIdentifiers.add(Map.entry(
                    reservation.confirmationNumber(), reservation.confirmationNumberType()))) {
                throw new IllegalArgumentException("Duplicate reservation confirmation number and type");
            }

            validateSegments(reservation);

            LocalDate today = LocalDate.now();

            LocalDate latestArrivalDate = reservation.segments().stream()
                    .map(ReviewedFlightSegment::arrivalDate)
                    .max(LocalDate::compareTo)
                    .orElseThrow();
            if (latestArrivalDate.isBefore(today)) {
                throw new IllegalArgumentException("Historical flight reservations cannot be confirmed");
            }
        }
    }

    private void validateSegments(ReviewedFlightReservation reservation) {
        Set<Integer> seenSegmentOrders = new HashSet<>();
        for (ReviewedFlightSegment segment : reservation.segments()) {
            if (segment == null) {
                throw new IllegalArgumentException("Reviewed flight segment must not be null");
            }
            if (segment.segmentOrder() == null || segment.segmentOrder() < 1) {
                throw new IllegalArgumentException("Flight segment order must be greater than zero");
            }
            if (!seenSegmentOrders.add(segment.segmentOrder())) {
                throw new IllegalArgumentException("Duplicate flight segment order within reservation");
            }
            if (segment.flightNumber() == null || segment.flightNumber().isBlank()) {
                throw new IllegalArgumentException("Flight number is required");
            }
            if (!isValidAirportCode(segment.departureAirportCode())) {
                throw new IllegalArgumentException("Departure airport code must be 3 letters");
            }
            if (!isValidAirportCode(segment.arrivalAirportCode())) {
                throw new IllegalArgumentException("Arrival airport code must be 3 letters");
            }
            if (segment.departureDate() == null) {
                throw new IllegalArgumentException("Departure date is required");
            }
            if (segment.departureTime() == null) {
                throw new IllegalArgumentException("Departure time is required");
            }
            if (segment.arrivalDate() == null) {
                throw new IllegalArgumentException("Arrival date is required");
            }
            if (segment.arrivalTime() == null) {
                throw new IllegalArgumentException("Arrival time is required");
            }
        }
    }

    private boolean isValidAirportCode(String airportCode) {
        return airportCode != null && airportCode.matches("[A-Za-z]{3}");
    }
}
