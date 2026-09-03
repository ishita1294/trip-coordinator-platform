package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.Destination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DestinationRepository extends JpaRepository<Destination, Long> {

    List<Destination> findByTrip_Id(Long tripId);

    Optional<Destination> findByIdAndTrip_Id(Long destinationId, Long tripId);
}
