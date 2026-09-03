package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TripMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    boolean existsByTrip_IdAndUser_Id(Long tripId, Long userId);

    List<TripMember> findByTrip_Id(Long tripId);

    Optional<TripMember> findByTrip_IdAndUser_Id(Long tripId, Long userId);
}
