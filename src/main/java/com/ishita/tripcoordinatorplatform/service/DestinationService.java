package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.Destination;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.repository.DestinationRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class DestinationService {

    private final DestinationRepository destinationRepository;
    private final TripRepository tripRepository;

    public DestinationService(
            DestinationRepository destinationRepository,
            TripRepository tripRepository
    ) {
        this.destinationRepository = destinationRepository;
        this.tripRepository = tripRepository;
    }

    public Destination createDestination(Long tripId, Destination destination) {

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        if (destination.getArrivalDate().isBefore(trip.getStartDate())
                || destination.getArrivalDate().isAfter(trip.getEndDate())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Destination arrival date must be within trip dates"
            );
        }

        if (destination.getDepartureDate() != null) {

            if (destination.getDepartureDate().isBefore(destination.getArrivalDate())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Destination departure date cannot be before arrival date"
                );
            }

            if (destination.getDepartureDate().isAfter(trip.getEndDate())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Destination departure date must be within trip dates"
                );
            }
        }

        destination.setTrip(trip);

        return destinationRepository.save(destination);
    }

    public List<Destination> getDestinations(Long tripId) {

        if (!tripRepository.existsById(tripId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Trip not found"
            );
        }

        return destinationRepository.findByTrip_Id(tripId);
    }
    public Destination updateDestination(
            Long tripId,
            Long destinationId,
            Destination updatedDestination
    ) {

        Destination existingDestination =
        destinationRepository.findByIdAndTrip_Id(destinationId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Destination not found"
                        )
                );

        Trip trip = existingDestination.getTrip();

        if (updatedDestination.getArrivalDate().isBefore(trip.getStartDate())
                || updatedDestination.getArrivalDate().isAfter(trip.getEndDate())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Destination arrival date must be within trip dates"
            );
        }

        if (updatedDestination.getDepartureDate() != null) {

            if (updatedDestination.getDepartureDate()
                    .isBefore(updatedDestination.getArrivalDate())) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Destination departure date cannot be before arrival date"
                );
            }

            if (updatedDestination.getDepartureDate()
                    .isAfter(trip.getEndDate())) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Destination departure date must be within trip dates"
                );
            }
        }

        existingDestination.setName(updatedDestination.getName());
        existingDestination.setCountry(updatedDestination.getCountry());
        existingDestination.setArrivalDate(updatedDestination.getArrivalDate());
        existingDestination.setDepartureDate(updatedDestination.getDepartureDate());
        existingDestination.setTimezone(updatedDestination.getTimezone());

        return destinationRepository.save(existingDestination);
    }

    public void deleteDestination(Long tripId, Long destinationId) {

        Destination destination = destinationRepository
                .findByIdAndTrip_Id(destinationId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Destination not found for this trip"
                        )
                );

        destinationRepository.delete(destination);
    }

}
