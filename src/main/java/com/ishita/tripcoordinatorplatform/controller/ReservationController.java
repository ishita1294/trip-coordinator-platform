package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.Reservation;
import com.ishita.tripcoordinatorplatform.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ReservationController {

    private final CoreTripOperations authorization;
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.reservationService = reservationService;
    }

    @PostMapping("/trips/{tripId}/reservations")
    public Reservation createReservation(
        @PathVariable Long tripId,
        @Valid @RequestBody Reservation reservation
    ) {
        return authorization.write(tripId, () -> {
            authorization.newEntity(reservation.getId());
            return reservationService.createReservation(tripId, reservation);
        });
    }

    @GetMapping("/trips/{tripId}/reservations")
    public List<Reservation> getReservations(@PathVariable Long tripId) {
        return authorization.read(tripId, () -> {
            return reservationService.getReservations(tripId);
        });
    }

    @PutMapping("/trips/{tripId}/reservations/{reservationId}")
    public Reservation updateReservation(
        @PathVariable Long tripId,
        @PathVariable Long reservationId,
        @Valid @RequestBody Reservation updatedReservation
    ) {
        return authorization.write(tripId, () -> {
            authorization.reservation(tripId, reservationId);
            return reservationService.updateReservation(
                tripId,
                reservationId,
                updatedReservation
            );
        });
    }

    @DeleteMapping("/trips/{tripId}/reservations/{reservationId}")
    public void deleteReservation(
        @PathVariable Long tripId,
        @PathVariable Long reservationId
    ) {
        authorization.write(tripId, () -> {
            authorization.reservation(tripId, reservationId);
            reservationService.deleteReservation(tripId, reservationId);
            return null;
        });
    }
}
