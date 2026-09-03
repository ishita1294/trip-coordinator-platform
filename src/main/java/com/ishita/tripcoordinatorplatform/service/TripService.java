package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.TripMemberRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TripService {

    private final List<Trip> trips = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong();

  /*  public Trip createTrip(Trip trip) {
        trip.setId(idSequence.incrementAndGet());
        trips.add(trip);
        return trip;
    }*/
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final TripMemberRepository tripMemberRepository;

    public TripService(
            TripRepository tripRepository,
            UserRepository userRepository,
            TripMemberRepository tripMemberRepository
    ) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.tripMemberRepository = tripMemberRepository;
    }

    public Trip createTrip(Trip trip, Long userId) {
        if (trip.getEndDate().isBefore(trip.getStartDate())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "End date cannot be before start date"
            );
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "User not found"
                        )
                );

        trip.setStatus(TripStatus.PLANNING);

        Trip savedTrip = tripRepository.save(trip);

        TripMember owner = new TripMember();
        owner.setTrip(savedTrip);
        owner.setUser(user);
        owner.setRole(TripMemberRole.OWNER);

        tripMemberRepository.save(owner);

        return savedTrip;
    }

    public List<Trip> getAllTrips() {
        return tripRepository.findAll();
    }

    public Trip getTripById(Long id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Trip not found"
                ));
    }

   /* public List<Trip> getAllTrips() {
        return new ArrayList<>(trips);
    }*/

    public Trip updateTrip(Long id, Trip updatedTrip) {

        Trip existingTrip = tripRepository.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        if (updatedTrip.getEndDate().isBefore(updatedTrip.getStartDate())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "End date cannot be before start date"
            );
        }

        existingTrip.setName(updatedTrip.getName());
       // existingTrip.setDestination(updatedTrip.getDestination());
        existingTrip.setStartDate(updatedTrip.getStartDate());
        existingTrip.setEndDate(updatedTrip.getEndDate());

        return tripRepository.save(existingTrip);
    }

    public void deleteTrip(Long id) {

        if (!tripRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Trip not found"
            );
        }

        tripRepository.deleteById(id);
    }
}
