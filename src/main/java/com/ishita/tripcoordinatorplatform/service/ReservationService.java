package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.Reservation;
import com.ishita.tripcoordinatorplatform.model.ReservationStatus;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;

    public ReservationService(
            ReservationRepository reservationRepository,
            TripRepository tripRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.tripRepository = tripRepository;
    }

    public Reservation createReservation(
            Long tripId,
            Reservation reservation
    ) {

        if (reservation.getConfirmationNumber() != null
                && !reservation.getConfirmationNumber().isBlank()
                && reservationRepository.existsByTrip_IdAndConfirmationNumber(
                tripId,
                reservation.getConfirmationNumber()
        )) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reservation with this confirmation number already exists"
            );
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        if (reservation.getEndDateTime() != null
                && reservation.getEndDateTime().isBefore(reservation.getStartDateTime())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reservation end time cannot be before start time"
            );
        }

        reservation.setTrip(trip);
        reservation.setStatus(ReservationStatus.PENDING);

        return reservationRepository.save(reservation);
    }

    public List<Reservation> getReservations(Long tripId) {

        if (!tripRepository.existsById(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Trip not found"
            );
        }

        return reservationRepository.findByTrip_Id(tripId);
    }

    public Reservation updateReservation(
            Long tripId,
            Long reservationId,
            Reservation updatedReservation
    ) {

        if (updatedReservation.getEndDateTime() != null
                && updatedReservation.getEndDateTime()
                .isBefore(updatedReservation.getStartDateTime())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reservation end time cannot be before start time"
            );
        }

        Reservation existingReservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Reservation not found"
                        )
                );

        if (!existingReservation.getTrip().getId().equals(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Reservation not found for this trip"
            );
        }

        existingReservation.setType(updatedReservation.getType());
        existingReservation.setName(updatedReservation.getName());
        existingReservation.setStartDateTime(updatedReservation.getStartDateTime());
        existingReservation.setEndDateTime(updatedReservation.getEndDateTime());
        existingReservation.setProvider(updatedReservation.getProvider());
        existingReservation.setConfirmationNumber(updatedReservation.getConfirmationNumber());
        existingReservation.setLocation(updatedReservation.getLocation());

        return reservationRepository.save(existingReservation);
    }

    public void deleteReservation(
            Long tripId,
            Long reservationId
    ) {

        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Reservation not found"
                        )
                );

        if (!reservation.getTrip().getId().equals(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Reservation not found for this trip"
            );
        }

        reservationRepository.delete(reservation);
    }
}
