package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.ItineraryConflictRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemParticipantRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemRepository;
import com.ishita.tripcoordinatorplatform.response.ItineraryConflictResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ItineraryConflictService {

    private final ItineraryItemParticipantRepository participantRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final ItineraryConflictRepository conflictRepository;

    public ItineraryConflictService(ItineraryItemParticipantRepository participantRepository,
                                    ItineraryItemRepository itineraryItemRepository,
                                    ItineraryConflictRepository conflictRepository) {
        this.participantRepository = participantRepository;
        this.itineraryItemRepository = itineraryItemRepository;
        this.conflictRepository = conflictRepository;
    }

    public boolean haveSharedParticipant(
            Long firstItemId,
            Long secondItemId
    ){
        List<ItineraryItemParticipant> firstItemParticipants =
                participantRepository.findByItineraryItem_Id(firstItemId);

        List<ItineraryItemParticipant> secondItemParticipants =
                participantRepository.findByItineraryItem_Id(secondItemId);

        Set<Long> firstItemMemberIds = firstItemParticipants.stream()
                .map(participant -> participant.getTripMember().getId())
                .collect(Collectors.toSet());

        return secondItemParticipants.stream()
                .map(participant -> participant.getTripMember().getId())
                .anyMatch(firstItemMemberIds::contains);
    }

    public List<ItineraryItem> findParticipantConflicts(
            Long tripId,
            Long itineraryItemId,
            Long tripMemberId
    ) {
        ItineraryItem currentItem = itineraryItemRepository
                .findByIdAndItinerary_Trip_Id(itineraryItemId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Itinerary item not found for this trip"
                ));

        List<ItineraryItemParticipant> memberParticipations =
                participantRepository.findByTripMember_Id(tripMemberId);

        List<ItineraryItem> conflictingItems = new ArrayList<>();

        for (ItineraryItemParticipant participation : memberParticipations) {

            ItineraryItem otherItem = participation.getItineraryItem();

            if (otherItem.getId().equals(itineraryItemId)) {
                continue;
            }

            boolean overlaps =
                    currentItem.getStartDateTime()
                            .isBefore(otherItem.getEndDateTime())
                            &&
                            currentItem.getEndDateTime()
                                    .isAfter(otherItem.getStartDateTime());

            if (overlaps) {
                conflictingItems.add(otherItem);
            }
        }

        return conflictingItems;
    }

    public void createMemberOverlapConflicts(
            ItineraryItem currentItem,
            TripMember tripMember,
            List<ItineraryItem> conflictingItems
    ) {
        for (ItineraryItem conflictingItem : conflictingItems) {

            ItineraryConflict conflict = new ItineraryConflict();

            conflict.setType(ItineraryConflictType.MEMBER_OVERLAP);
            conflict.setStatus(ItineraryConflictStatus.OPEN);
            conflict.setFirstItem(currentItem);
            conflict.setSecondItem(conflictingItem);
            conflict.setTripMember(tripMember);

            conflictRepository.save(conflict);
        }
    }

    public List<ItineraryConflictResponse> getConflictsForMember(
            Long tripId,
            Long tripMemberId
    ) {
        List<ItineraryConflict> conflicts = conflictRepository
                .findByFirstItem_Itinerary_Trip_IdAndTripMember_Id(
                        tripId,
                        tripMemberId
                );
        List<ItineraryConflictResponse> responses = new ArrayList<>();

        for (ItineraryConflict conflict : conflicts) {
            responses.add(toResponse(conflict));
        }

        return responses;

    }

    private ItineraryConflictResponse toResponse(
            ItineraryConflict conflict
    ) {
        ItineraryConflictResponse response =
                new ItineraryConflictResponse();

        response.setId(conflict.getId());
        response.setType(conflict.getType());
        response.setStatus(conflict.getStatus());
        response.setTripMemberId(conflict.getTripMember().getId());

        response.setMemberName(conflict.getTripMember().getUser().getName());
        response.setFirstItemId(conflict.getFirstItem().getId());

        response.setFirstItemTitle(conflict.getFirstItem().getTitle());
        response.setSecondItemId(conflict.getSecondItem().getId());
        response.setSecondItemTitle(conflict.getSecondItem().getTitle());
        return response;
    }
}
