package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.FlightSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlightSegmentRepository extends JpaRepository<FlightSegment, Long> {

    List<FlightSegment> findByReservation_IdOrderBySegmentOrder(Long reservationId);

    boolean existsByReservation_IdAndSegmentOrder(
            Long reservationId,
            Integer segmentOrder
    );
}
