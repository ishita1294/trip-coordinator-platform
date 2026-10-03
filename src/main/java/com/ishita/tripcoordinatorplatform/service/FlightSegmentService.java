package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.FlightSegment;
import com.ishita.tripcoordinatorplatform.model.Reservation;
import com.ishita.tripcoordinatorplatform.model.ReservationType;
import com.ishita.tripcoordinatorplatform.repository.FlightSegmentRepository;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FlightSegmentService {

    private final FlightSegmentRepository flightSegmentRepository;
    private final ReservationRepository reservationRepository;

    public FlightSegmentService(
            FlightSegmentRepository flightSegmentRepository,
            ReservationRepository reservationRepository
    ) {
        this.flightSegmentRepository = flightSegmentRepository;
        this.reservationRepository = reservationRepository;
    }

    public FlightSegment createFlightSegment(
            Long tripId,
            Long reservationId,
            FlightSegment segment
    ) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Reservation not found"
                        )
                );

        // A segment must stay within the trip that owns its parent reservation.
        if (!reservation.getTrip().getId().equals(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reservation does not belong to this trip"
            );
        }

        // Flight-specific structure must only be attached to flight reservations.
        if (reservation.getType() != ReservationType.FLIGHT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Flight segment can only belong to a flight reservation"
            );
        }

        if (segment.getSegmentOrder() == null || segment.getSegmentOrder() < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Flight segment order must be greater than zero"
            );
        }

        // Segment order must be unique within the same flight reservation.
        if (flightSegmentRepository.existsByReservation_IdAndSegmentOrder(
                reservationId,
                segment.getSegmentOrder()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Flight segment order already exists for this reservation"
            );
        }

        if (segment.getFlightNumber() == null
                || segment.getFlightNumber().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Flight number is required"
            );
        }

        if (segment.getDepartureAirportCode() == null
                || segment.getDepartureAirportCode().isBlank()
                || segment.getArrivalAirportCode() == null
                || segment.getArrivalAirportCode().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Departure and arrival airport codes are required"
            );
        }

        if (segment.getDepartureDateTime() == null
                || segment.getArrivalDateTime() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Departure and arrival times are required"
            );
        }

        segment.setReservation(reservation);

        return flightSegmentRepository.save(segment);
    }

    public List<FlightSegment> getFlightSegments(
            Long tripId,
            Long reservationId
    ) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Reservation not found"
                        )
                );

        // Keep access scoped to the trip that owns the reservation.
        if (!reservation.getTrip().getId().equals(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reservation does not belong to this trip"
            );
        }

        if (reservation.getType() != ReservationType.FLIGHT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Flight segments can only belong to a flight reservation"
            );
        }

        return flightSegmentRepository
                .findByReservation_IdOrderBySegmentOrder(reservationId);
    }
}
