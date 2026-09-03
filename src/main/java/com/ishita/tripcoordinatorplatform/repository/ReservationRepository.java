package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByTrip_Id(Long tripId);

    boolean existsByTrip_IdAndConfirmationNumber(
            Long tripId,
            String confirmationNumber
    );
}
