package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.ItineraryItemParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemParticipantRepository extends JpaRepository<ItineraryItemParticipant, Long> {
    boolean existsByTripMember_Id(Long tripMemberId);


    List<ItineraryItemParticipant> findByItineraryItem_Id(Long itineraryItemId);

    @org.springframework.data.jpa.repository.EntityGraph(type = org.springframework.data.jpa.repository.EntityGraph.EntityGraphType.LOAD, attributePaths = {"itineraryItem.itinerary.trip", "itineraryItem.activity.destination", "itineraryItem.reservation"})
    List<ItineraryItemParticipant> findByTripMember_Id(Long tripMemberId);

    @org.springframework.data.jpa.repository.Query("select distinct p.itineraryItem.id from ItineraryItemParticipant p where p.itineraryItem.itinerary.trip.id = :tripId")
    List<Long> findAssignedItemIdsByTripId(@org.springframework.data.repository.query.Param("tripId") Long tripId);

    boolean existsByItineraryItem_IdAndTripMember_Id(
            Long itineraryItemId,
            Long tripMemberId
    );

}
