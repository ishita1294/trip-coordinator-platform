package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.ItineraryConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryConflictRepository extends JpaRepository<ItineraryConflict, Long> {

    List<ItineraryConflict> findByFirstItem_Id(Long firstItemId);

    List<ItineraryConflict> findBySecondItem_Id(Long secondItemId);

    List<ItineraryConflict> findByTripMember_Id(Long tripMemberId);

    List<ItineraryConflict> findByFirstItem_Itinerary_Trip_IdAndTripMember_Id(
            Long tripId,
            Long tripMemberId
    );

    List<ItineraryConflict> findByFirstItem_IdOrSecondItem_Id(
            Long firstItemId,
            Long secondItemId
    );
}
