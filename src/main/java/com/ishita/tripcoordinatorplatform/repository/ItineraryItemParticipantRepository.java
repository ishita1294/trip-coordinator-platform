package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.ItineraryItemParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemParticipantRepository extends JpaRepository<ItineraryItemParticipant, Long> {

    List<ItineraryItemParticipant> findByItineraryItem_Id(Long itineraryItemId);

    List<ItineraryItemParticipant> findByTripMember_Id(Long tripMemberId);

    boolean existsByItineraryItem_IdAndTripMember_Id(
            Long itineraryItemId,
            Long tripMemberId
    );

}
