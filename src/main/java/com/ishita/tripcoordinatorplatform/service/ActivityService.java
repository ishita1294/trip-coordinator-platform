package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.Activity;
import com.ishita.tripcoordinatorplatform.model.ActivityStatus;
import com.ishita.tripcoordinatorplatform.model.Destination;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.repository.ActivityRepository;
import com.ishita.tripcoordinatorplatform.repository.DestinationRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ActivityService {
    private final ActivityRepository activityRepository;
    private final TripRepository tripRepository;
    private final DestinationRepository destinationRepository;

    public ActivityService(
            ActivityRepository activityRepository,
            TripRepository tripRepository,
            DestinationRepository destinationRepository
    ) {
        this.activityRepository = activityRepository;
        this.tripRepository = tripRepository;
        this.destinationRepository = destinationRepository;
    }

    public Activity createActivity(
            Long tripId,
            Long destinationId,
            Activity activity
    ) {

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        Destination destination = destinationRepository
                .findByIdAndTrip_Id(destinationId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Destination not found for this trip"
                        )
                );

        activity.setTrip(trip);
        activity.setDestination(destination);
        activity.setStatus(ActivityStatus.SUGGESTED);

        return activityRepository.save(activity);
    }

    public List<Activity> getActivities(
            Long tripId,
            Long destinationId
    ) {

        destinationRepository.findByIdAndTrip_Id(destinationId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Destination not found for this trip"
                        )
                );

        return activityRepository
                .findByTrip_IdAndDestination_Id(tripId, destinationId);
    }

    public Activity updateActivity(
            Long tripId,
            Long destinationId,
            Long activityId,
            Activity updatedActivity
    ) {

        Activity existingActivity = activityRepository.findById(activityId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Activity not found"
                        )
                );

        if (!existingActivity.getTrip().getId().equals(tripId)
                || !existingActivity.getDestination().getId().equals(destinationId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Activity not found for this trip and destination"
            );
        }

        existingActivity.setName(updatedActivity.getName());
        existingActivity.setDescription(updatedActivity.getDescription());
        existingActivity.setCategory(updatedActivity.getCategory());
        existingActivity.setEstimatedDurationMinutes(updatedActivity.getEstimatedDurationMinutes());
        existingActivity.setEstimatedCost(updatedActivity.getEstimatedCost());

        return activityRepository.save(existingActivity);
    }

    public void deleteActivity(
            Long tripId,
            Long destinationId,
            Long activityId
    ) {

        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Activity not found"
                        )
                );

        if (!activity.getTrip().getId().equals(tripId)
                || !activity.getDestination().getId().equals(destinationId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Activity not found for this trip and destination"
            );
        }

        activityRepository.delete(activity);
    }
}