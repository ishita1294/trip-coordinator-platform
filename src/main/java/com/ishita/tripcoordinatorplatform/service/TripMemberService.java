package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.TripMemberRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import com.ishita.tripcoordinatorplatform.auth.CurrentUser;
import com.ishita.tripcoordinatorplatform.auth.TripAccessService;
import com.ishita.tripcoordinatorplatform.repository.ItineraryItemParticipantRepository;
import com.ishita.tripcoordinatorplatform.repository.ItineraryConflictRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
public class TripMemberService {

    private final CurrentUser currentUser;
    private final TripAccessService access;
    private final ItineraryItemParticipantRepository participants;
    private final ItineraryConflictRepository conflicts;
    private final TripMemberRepository tripMemberRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;

    public TripMemberService(
            TripMemberRepository tripMemberRepository,
            TripRepository tripRepository,
            UserRepository userRepository,
            CurrentUser currentUser,
            TripAccessService access,
            ItineraryItemParticipantRepository participants,
            ItineraryConflictRepository conflicts
    ) {
        this.currentUser = currentUser;
        this.access = access;
        this.participants = participants;
        this.conflicts = conflicts;
        this.tripMemberRepository = tripMemberRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
    }

    // Every membership change locks the trip before reading the acting user's or target user's membership.
    // Do not load membership entities before this lock, even when the persistence context lasts for the whole request.
    private Trip requireOwnerForWrite(Long tripId) {
        Long actorId = currentUser.require().getId();
        Trip trip = tripRepository.lockForMembershipChange(tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
        access.requireOwner(tripId, actorId);
        return trip;
    }

    @Transactional
    public TripMember addMember(Long tripId, Long userId, TripMemberRole role) {

        Trip trip = requireOwnerForWrite(tripId);
        if (role == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership role is required");
        if (tripMemberRepository.existsByTrip_IdAndUser_Id(tripId, userId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User is already a member of this trip"
            );
        }

        if (trip.getTripType() == TripType.SOLO) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot add members to a solo trip"
            );
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "User not found"
                        )
                );

        TripMember tripMember = new TripMember();
        tripMember.setTrip(trip);
        tripMember.setUser(user);
        tripMember.setRole(role);

        return tripMemberRepository.save(tripMember);
    }

    @Transactional(readOnly = true)
    public List<TripMember> getMembers(Long tripId) {
        access.requireMember(tripId, currentUser.require().getId());

        return tripMemberRepository.findByTrip_Id(tripId);
    }

    @Transactional
    public void removeMember(Long tripId, Long userId) {
        requireOwnerForWrite(tripId);

        TripMember tripMember = tripMemberRepository
                .findByTrip_IdAndUser_Id(tripId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip member not found"
                        )
                );

        if (tripMember.getRole() == TripMemberRole.OWNER) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trip owner cannot be removed"
            );
        }
        if (participants.existsByTripMember_Id(tripMember.getId()) || conflicts.existsByTripMember_Id(tripMember.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Member has itinerary assignments or conflicts; resolve those before removal");
        }
        tripMemberRepository.delete(tripMember);
    }

    @Transactional
    public TripMember updateMemberRole(
            Long tripId,
            Long userId,
            TripMemberRole newRole
    ) {
        requireOwnerForWrite(tripId);
        if (newRole == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership role is required");
        TripMember tripMember = tripMemberRepository
                .findByTrip_IdAndUser_Id(tripId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip member not found"
                        )
                );

        if (tripMember.getRole() == TripMemberRole.OWNER
                && newRole != TripMemberRole.OWNER) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trip owner role cannot be changed"
            );
        }

        tripMember.setRole(newRole);

        return tripMemberRepository.save(tripMember);
    }
}
