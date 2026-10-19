package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Trip t where t.id = :tripId")
    java.util.Optional<Trip> lockForMembershipChange(@org.springframework.data.repository.query.Param("tripId") Long tripId);

    @org.springframework.data.jpa.repository.Query("select m.trip from TripMember m where m.user.id = :userId order by m.trip.id")
    java.util.List<Trip> findForMember(@org.springframework.data.repository.query.Param("userId") Long userId);
}
