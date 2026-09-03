package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.Itinerary;
import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findByItinerary_Trip_IdOrderByStartDateTime(Long tripId);

    Optional<ItineraryItem> findByIdAndItinerary_Trip_Id(
            Long itemId,
            Long tripId
    );
}
