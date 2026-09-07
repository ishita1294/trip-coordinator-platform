package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.ActivityRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryRepository;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ItineraryItemService {

    private final ItineraryItemRepository itineraryItemRepository;
    private final ItineraryRepository itineraryRepository;
    private final ActivityRepository activityRepository;
    private final ReservationRepository reservationRepository;
    private final ItineraryConflictService itineraryConflictService;

    public ItineraryItemService(
            ItineraryItemRepository itineraryItemRepository,
            ItineraryRepository itineraryRepository,
            ActivityRepository activityRepository,
            ReservationRepository reservationRepository,
            ItineraryConflictService itineraryConflictService
    ) {
        this.itineraryItemRepository = itineraryItemRepository;
        this.itineraryRepository = itineraryRepository;
        this.activityRepository = activityRepository;
        this.reservationRepository = reservationRepository;
        this.itineraryConflictService = itineraryConflictService;
    }

    public ItineraryItem createItineraryItem(
            Long tripId,
            Long activityId,
            Long reservationId,
            ItineraryItem item
    ) {

        if (item.getEndDateTime().isBefore(item.getStartDateTime())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item end time cannot be before start time"
            );
        }



        Itinerary itinerary = itineraryRepository.findByTrip_Id(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Itinerary not found for this trip"
                        )
                );

        if (activityId != null && reservationId != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item cannot reference both an activity and a reservation"
            );
        }

        if (activityId != null) {

            Activity activity = activityRepository.findById(activityId)
                    .orElseThrow(() ->
                            new ResponseStatusException(
                                    HttpStatus.NOT_FOUND,
                                    "Activity not found"
                            )
                    );

            if (!activity.getTrip().getId().equals(tripId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Activity does not belong to this trip"
                );
            }

            item.setActivity(activity);
        }

        if (reservationId != null) {

            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() ->
                            new ResponseStatusException(
                                    HttpStatus.NOT_FOUND,
                                    "Reservation not found"
                            )
                    );

            if (!reservation.getTrip().getId().equals(tripId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Reservation does not belong to this trip"
                );
            }

            item.setReservation(reservation);
        }

        Trip trip = itinerary.getTrip();

        if (item.getStartDateTime().toLocalDate().isBefore(trip.getStartDate())
                || item.getStartDateTime().toLocalDate().isAfter(trip.getEndDate())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item must be within trip dates"
            );
        }

        item.setItinerary(itinerary);

        if (item.getEndDateTime() != null
                && item.getEndDateTime().isBefore(item.getStartDateTime())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item end time cannot be before start time"
            );
        }

        return itineraryItemRepository.save(item);
    }

    public List<ItineraryItem> getItineraryItems(Long tripId) {


        if (itineraryRepository.findByTrip_Id(tripId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Itinerary not found for this trip"
            );
        }

        return itineraryItemRepository
                .findByItinerary_Trip_IdOrderByStartDateTime(tripId);
    }

    public ItineraryItem updateItineraryItem(
            Long tripId,
            Long itemId,
            Long activityId,
            Long reservationId,
            ItineraryItem updatedItem
    ) {

        ItineraryItem existingItem =
                itineraryItemRepository
                        .findByIdAndItinerary_Trip_Id(itemId, tripId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Itinerary item not found"
                                )
                        );

        if (activityId != null && reservationId != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item cannot reference both an activity and a reservation"
            );
        }

        if (updatedItem.getEndDateTime()
                .isBefore(updatedItem.getStartDateTime())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item end time cannot be before start time"
            );
        }
        Trip trip = existingItem.getItinerary().getTrip();

        if (updatedItem.getStartDateTime().toLocalDate().isBefore(trip.getStartDate())
                || updatedItem.getEndDateTime().toLocalDate().isAfter(trip.getEndDate())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item must be within trip dates"
            );
        }

        List<ItineraryItem> existingItems =
                itineraryItemRepository
                        .findByItinerary_Trip_IdOrderByStartDateTime(tripId);

        for (ItineraryItem otherItem : existingItems) {

            if (otherItem.getId().equals(itemId)) {
                continue;
            }

            boolean overlaps =
                    updatedItem.getStartDateTime()
                            .isBefore(otherItem.getEndDateTime())
                            &&
                            updatedItem.getEndDateTime()
                                    .isAfter(otherItem.getStartDateTime());

            if (overlaps) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Itinerary item overlaps with an existing item"
                );
            }
        }

        if (activityId != null) {

            Activity activity = activityRepository.findById(activityId)
                    .orElseThrow(() ->
                            new ResponseStatusException(
                                    HttpStatus.NOT_FOUND,
                                    "Activity not found"
                            )
                    );

            if (!activity.getTrip().getId().equals(tripId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Activity does not belong to this trip"
                );
            }

            existingItem.setActivity(activity);
            existingItem.setReservation(null);
        }

        if (reservationId != null) {

            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() ->
                            new ResponseStatusException(
                                    HttpStatus.NOT_FOUND,
                                    "Reservation not found"
                            )
                    );

            if (!reservation.getTrip().getId().equals(tripId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Reservation does not belong to this trip"
                );
            }

            existingItem.setReservation(reservation);
            existingItem.setActivity(null);
        }

        existingItem.setTitle(updatedItem.getTitle());
        existingItem.setStartDateTime(updatedItem.getStartDateTime());
        existingItem.setEndDateTime(updatedItem.getEndDateTime());

        return itineraryItemRepository.save(existingItem);

    }

    public void deleteItineraryItem(Long tripId, Long itemId) {

        ItineraryItem existingItem =
                itineraryItemRepository
                        .findByIdAndItinerary_Trip_Id(itemId, tripId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Itinerary item not found"
                                )
                        );

        itineraryItemRepository.delete(existingItem);
    }
}
