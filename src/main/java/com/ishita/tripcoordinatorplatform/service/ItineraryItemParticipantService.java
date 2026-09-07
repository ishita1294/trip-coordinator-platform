package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.model.ItineraryItemParticipant;
import com.ishita.tripcoordinatorplatform.model.TripMember;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemParticipantRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemRepository;
import com.ishita.tripcoordinatorplatform.repository.TripMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ItineraryItemParticipantService {

    private final ItineraryItemParticipantRepository participantRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final TripMemberRepository tripMemberRepository;
    private final ItineraryConflictService itineraryConflictService;

    public ItineraryItemParticipantService(
            ItineraryItemParticipantRepository participantRepository,
            ItineraryItemRepository itineraryItemRepository,
            TripMemberRepository tripMemberRepository,
            ItineraryConflictService itineraryConflictService
    ) {
        this.participantRepository = participantRepository;
        this.itineraryItemRepository = itineraryItemRepository;
        this.tripMemberRepository = tripMemberRepository;
        this.itineraryConflictService = itineraryConflictService;
    }

    @Transactional
    public ItineraryItemParticipant addParticipant(
            Long tripId,
            Long itineraryItemId,
            Long tripMemberId
    ) {
        ItineraryItem itineraryItem = itineraryItemRepository.findByIdAndItinerary_Trip_Id(itineraryItemId,tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Itinerary item not found for this trip"
                        )
                );

        TripMember tripMember = tripMemberRepository.findById(tripMemberId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip member not found"
                        )
                );

        Long itemTripId =
                itineraryItem.getItinerary().getTrip().getId();

        Long memberTripId =
                tripMember.getTrip().getId();

        if (!itemTripId.equals(memberTripId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trip member does not belong to this itinerary item's trip"
            );
        }

        if (participantRepository
                .existsByItineraryItem_IdAndTripMember_Id(
                        itineraryItemId,
                        tripMemberId
                )) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Trip member is already a participant in this itinerary item"
            );
        }

        List<ItineraryItem> conflictingItems =
                itineraryConflictService.findParticipantConflicts(
                        tripId,
                        itineraryItemId,
                        tripMemberId
                );

        ItineraryItemParticipant participant =
                new ItineraryItemParticipant();

        participant.setItineraryItem(itineraryItem);
        participant.setTripMember(tripMember);

        ItineraryItemParticipant savedParticipant =
                participantRepository.save(participant);

        if (!conflictingItems.isEmpty()) {
            itineraryConflictService.createMemberOverlapConflicts(
                    itineraryItem,
                    tripMember,
                    conflictingItems
            );
        }

        return savedParticipant;
    }

    public List<ItineraryItemParticipant> getParticipants(
            Long tripId,
            Long itineraryItemId
    ) {

        itineraryItemRepository
                .findByIdAndItinerary_Trip_Id(itineraryItemId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Itinerary item not found for this trip"
                ));

        return participantRepository.findByItineraryItem_Id(itineraryItemId);
    }
}
