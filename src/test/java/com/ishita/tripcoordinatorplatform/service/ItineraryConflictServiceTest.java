package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.model.ItineraryItemParticipant;
import com.ishita.tripcoordinatorplatform.model.TripMember;
import com.ishita.tripcoordinatorplatform.repository.ItineraryConflictRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemParticipantRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ItineraryConflictServiceTest {

    @Test
    void shouldReturnTrueWhenItemsShareParticipant() {
        ItineraryItemParticipantRepository participantRepository =
                mock(ItineraryItemParticipantRepository.class);

        ItineraryItemRepository itineraryItemRepository =
                mock(ItineraryItemRepository.class);

        ItineraryConflictRepository conflictRepository =
                mock(ItineraryConflictRepository.class);

        ItineraryConflictService conflictService =
                new ItineraryConflictService(participantRepository, itineraryItemRepository, conflictRepository);

        TripMember sharedMember = new TripMember();
        sharedMember.setId(1L);

        ItineraryItemParticipant firstParticipant =
                new ItineraryItemParticipant();
        firstParticipant.setTripMember(sharedMember);

        ItineraryItemParticipant secondParticipant =
                new ItineraryItemParticipant();
        secondParticipant.setTripMember(sharedMember);

        when(participantRepository.findByItineraryItem_Id(1L))
                .thenReturn(List.of(firstParticipant));

        when(participantRepository.findByItineraryItem_Id(2L))
                .thenReturn(List.of(secondParticipant));

        boolean result = conflictService.haveSharedParticipant(1L, 2L);

        assertTrue(result);
    }

    @Test
    void shouldReturnFalseWhenItemsDoNotShareParticipant() {
        ItineraryItemParticipantRepository participantRepository =
                mock(ItineraryItemParticipantRepository.class);

        ItineraryItemRepository itineraryItemRepository =
                mock(ItineraryItemRepository.class);

        ItineraryConflictRepository conflictRepository =
                mock(ItineraryConflictRepository.class);


        ItineraryConflictService conflictService =
                new ItineraryConflictService(participantRepository, itineraryItemRepository, conflictRepository);

        TripMember firstMember = new TripMember();
        firstMember.setId(1L);

        TripMember secondMember = new TripMember();
        secondMember.setId(2L);

        ItineraryItemParticipant firstParticipant =
                new ItineraryItemParticipant();
        firstParticipant.setTripMember(firstMember);

        ItineraryItemParticipant secondParticipant =
                new ItineraryItemParticipant();
        secondParticipant.setTripMember(secondMember);

        when(participantRepository.findByItineraryItem_Id(1L))
                .thenReturn(List.of(firstParticipant));

        when(participantRepository.findByItineraryItem_Id(2L))
                .thenReturn(List.of(secondParticipant));

        boolean result = conflictService.haveSharedParticipant(1L, 2L);

        assertFalse(result);
    }

    @Test
    void shouldReturnOverlappingItemForParticipant() {

        ItineraryItemParticipantRepository participantRepository =
                mock(ItineraryItemParticipantRepository.class);

        ItineraryItemRepository itineraryItemRepository =
                mock(ItineraryItemRepository.class);

        ItineraryConflictRepository conflictRepository =
                mock(ItineraryConflictRepository.class);

        ItineraryConflictService conflictService =
                new ItineraryConflictService(
                        participantRepository,
                        itineraryItemRepository,
                        conflictRepository
                );

        ItineraryItem currentItem = mock(ItineraryItem.class);
        ItineraryItem otherItem = mock(ItineraryItem.class);

        ItineraryItemParticipant participation =
                mock(ItineraryItemParticipant.class);

        when(currentItem.getStartDateTime())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 9, 0));

        when(currentItem.getEndDateTime())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 10, 0));

        when(otherItem.getId()).thenReturn(2L);

        when(otherItem.getStartDateTime())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 9, 30));

        when(otherItem.getEndDateTime())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 11, 0));

        when(participation.getItineraryItem())
                .thenReturn(otherItem);

        when(itineraryItemRepository
                .findByIdAndItinerary_Trip_Id(1L, 1L))
                .thenReturn(Optional.of(currentItem));

        when(participantRepository.findByTripMember_Id(1L))
                .thenReturn(List.of(participation));

        List<ItineraryItem> conflicts =
                conflictService.findParticipantConflicts(
                        1L,
                        1L,
                        1L
                );

        assertEquals(1, conflicts.size());
        assertSame(otherItem, conflicts.get(0));
    }
}
