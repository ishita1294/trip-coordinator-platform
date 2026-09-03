package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.Reservation;
import com.ishita.tripcoordinatorplatform.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/trips/{tripId}/reservations")
    public Reservation createReservation(
            @PathVariable Long tripId,
            @Valid @RequestBody Reservation reservation
    ) {
        return reservationService.createReservation(tripId, reservation);
    }

    @GetMapping("/trips/{tripId}/reservations")
    public List<Reservation> getReservations(@PathVariable Long tripId) {
        return reservationService.getReservations(tripId);
    }

    @PutMapping("/trips/{tripId}/reservations/{reservationId}")
    public Reservation updateReservation(
            @PathVariable Long tripId,
            @PathVariable Long reservationId,
            @Valid @RequestBody Reservation updatedReservation
    ) {
        return reservationService.updateReservation(
                tripId,
                reservationId,
                updatedReservation
        );
    }

    @DeleteMapping("/trips/{tripId}/reservations/{reservationId}")
    public void deleteReservation(
            @PathVariable Long tripId,
            @PathVariable Long reservationId
    ) {
        reservationService.deleteReservation(tripId, reservationId);
    }
}
