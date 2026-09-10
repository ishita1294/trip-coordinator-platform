package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.CreateItineraryItemRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ItineraryItemService {

    private final ItineraryItemRepository itineraryItemRepository;
    private final ItineraryRepository itineraryRepository;
    private final ActivityRepository activityRepository;
    private final ReservationRepository reservationRepository;
    private final ItineraryConflictService itineraryConflictService;
    private final ItineraryItemParticipantService itineraryItemParticipantService;
    private final TripMemberRepository tripMemberRepository;
    private final ItineraryItemParticipantRepository participantRepository;

    public ItineraryItemService(
            ItineraryItemRepository itineraryItemRepository,
            ItineraryRepository itineraryRepository,
            ActivityRepository activityRepository,
            ReservationRepository reservationRepository,
            ItineraryConflictService itineraryConflictService,
            ItineraryItemParticipantService itineraryItemParticipantService,
            TripMemberRepository tripMemberRepository,
            ItineraryItemParticipantRepository participantRepository
    ) {
        this.itineraryItemRepository = itineraryItemRepository;
        this.itineraryRepository = itineraryRepository;
        this.activityRepository = activityRepository;
        this.reservationRepository = reservationRepository;
        this.itineraryConflictService = itineraryConflictService;
        this.itineraryItemParticipantService = itineraryItemParticipantService;
        this.tripMemberRepository = tripMemberRepository;
        this.participantRepository = participantRepository;
    }

    @Transactional
    public ItineraryItem createItineraryItem(
            Long tripId,
            CreateItineraryItemRequest request
    ) {

        ItineraryItem item = new ItineraryItem();

        item.setTitle(request.getTitle());
        item.setStartDateTime(request.getStartDateTime());
        item.setEndDateTime(request.getEndDateTime());
        item.setFlexibility(request.getFlexibility());

        Long activityId = request.getActivityId();
        Long reservationId = request.getReservationId();

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

        if (trip.getTripType() == TripType.GROUP
                && (request.getParticipantIds() == null
                || request.getParticipantIds().isEmpty())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At least one participant is required for a group trip itinerary item"
            );
        }

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

        if (exactDuplicateExists(trip, request)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Itinerary item already exists"
            );
        }

        ItineraryItem savedItem = itineraryItemRepository.save(item);

        assignParticipants(
                trip,
                savedItem,
                request.getParticipantIds()
        );

        return savedItem;
    }

    /**
     * Assigns participants when a new itinerary item is created.
     * Solo trips automatically use the owner; group trips require
     * at least one explicitly selected trip member.
     */
    private void assignParticipants(
            Trip trip,
            ItineraryItem itineraryItem,
            List<Long> participantIds
    ) {

        if (trip.getTripType() == TripType.SOLO) {

            TripMember owner = tripMemberRepository
                    .findByTrip_Id(trip.getId())
                    .stream()
                    .filter(member -> member.getRole() == TripMemberRole.OWNER)
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Solo trip does not have an owner"
                            )
                    );

            itineraryItemParticipantService.addParticipant(
                    trip.getId(),
                    itineraryItem.getId(),
                    owner.getId()
            );

            return;
        }

        for (Long participantId : participantIds) {
            itineraryItemParticipantService.addParticipant(
                    trip.getId(),
                    itineraryItem.getId(),
                    participantId
            );
        }
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

        validateWithinTripDates(trip, updatedItem.getStartDateTime(), updatedItem.getEndDateTime());

        List<ItineraryItem> existingItems =
                itineraryItemRepository
                        .findByItinerary_Trip_IdOrderByStartDateTime(tripId);


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

        ItineraryItem savedItem = itineraryItemRepository.save(existingItem);


        itineraryConflictService.reconcileConflictsForItem(tripId, savedItem.getId());

        return savedItem;
    }

    ItineraryItem updateItineraryItemTimes(
            Long tripId,
            ItineraryItem item,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    ) {

        item.setStartDateTime(startDateTime);
        item.setEndDateTime(endDateTime);

        ItineraryItem savedItem =
                itineraryItemRepository.save(item);

        itineraryConflictService.reconcileConflictsForItem(
                tripId,
                savedItem.getId()
        );

        return savedItem;
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

    /**
     * Normalizes an itinerary item title for duplicate comparison
     * without changing the value stored or displayed to the user.
     */
    private String normalizeTitle(String title) {
        return title
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Finds itinerary items with the same schedule and normalized title.
     * These are only duplicate candidates; participants and linked
     * activity/reservation still need to be compared.
     */
    private boolean exactDuplicateExists(
            Trip trip,
            CreateItineraryItemRequest request
    ) {
        List<ItineraryItem> candidates =
                itineraryItemRepository
                        .findByItinerary_Trip_IdAndStartDateTimeAndEndDateTime(
                                trip.getId(),
                                request.getStartDateTime(),
                                request.getEndDateTime()
                        );

        Set<Long> requestedParticipantIds;

        if (trip.getTripType() == TripType.SOLO) {
            Long ownerId = tripMemberRepository.findByTrip_Id(trip.getId())
                    .stream()
                    .filter(member -> member.getRole() == TripMemberRole.OWNER)
                    .map(TripMember::getId)
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Solo trip does not have an owner"
                            )
                    );

            requestedParticipantIds = Set.of(ownerId);
        } else {
            requestedParticipantIds =
                    new HashSet<>(request.getParticipantIds());
        }

        for (ItineraryItem existingItem : candidates) {

            boolean sameTitle =
                    normalizeTitle(existingItem.getTitle())
                            .equals(normalizeTitle(request.getTitle()));

            Long existingActivityId =
                    existingItem.getActivity() != null
                            ? existingItem.getActivity().getId()
                            : null;

            Long existingReservationId =
                    existingItem.getReservation() != null
                            ? existingItem.getReservation().getId()
                            : null;

            boolean sameSource =
                    Objects.equals(
                            existingActivityId,
                            request.getActivityId()
                    )
                            && Objects.equals(
                            existingReservationId,
                            request.getReservationId()
                    );

            Set<Long> existingParticipantIds =
                    participantRepository
                            .findByItineraryItem_Id(existingItem.getId())
                            .stream()
                            .map(participant ->
                                    participant.getTripMember().getId()
                            )
                            .collect(Collectors.toSet());

            if (sameTitle
                    && sameSource
                    && existingParticipantIds.equals(requestedParticipantIds)) {
                return true;
            }
        }

        return false;
    }

    void validateWithinTripDates(
            Trip trip,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    ) {

        if (startDateTime.toLocalDate().isBefore(trip.getStartDate())
                || endDateTime.toLocalDate().isAfter(trip.getEndDate())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary item must be within trip dates"
            );
        }
    }

}
