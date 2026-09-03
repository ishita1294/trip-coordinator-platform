package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.Itinerary;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.repository.ItineraryRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final TripRepository tripRepository;

    public ItineraryService(
            ItineraryRepository itineraryRepository,
            TripRepository tripRepository
    ) {
        this.itineraryRepository = itineraryRepository;
        this.tripRepository = tripRepository;
    }

    public Itinerary createItinerary(Long tripId) {

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        if (itineraryRepository.findByTrip_Id(tripId).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary already exists for this trip"
            );
        }

        Itinerary itinerary = new Itinerary();
        itinerary.setTrip(trip);

        return itineraryRepository.save(itinerary);
    }
}
