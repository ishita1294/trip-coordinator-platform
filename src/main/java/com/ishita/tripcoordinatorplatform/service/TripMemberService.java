package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.TripMemberRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TripMemberService {

    private final TripMemberRepository tripMemberRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;

    public TripMemberService(
            TripMemberRepository tripMemberRepository,
            TripRepository tripRepository,
            UserRepository userRepository
    ) {
        this.tripMemberRepository = tripMemberRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
    }

    public TripMember addMember(Long tripId, Long userId, TripMemberRole role) {

        if (tripMemberRepository.existsByTrip_IdAndUser_Id(tripId, userId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User is already a member of this trip"
            );
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );
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

    public List<TripMember> getMembers(Long tripId) {

        if (!tripRepository.existsById(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Trip not found"
            );
        }

        return tripMemberRepository.findByTrip_Id(tripId);
    }

    public void removeMember(Long tripId, Long userId) {

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
        tripMemberRepository.delete(tripMember);
    }

    public TripMember updateMemberRole(
            Long tripId,
            Long userId,
            TripMemberRole newRole
    ) {

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
