package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CoreTripOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import com.ishita.tripcoordinatorplatform.model.Activity;
import com.ishita.tripcoordinatorplatform.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class ActivityController {
    private final CoreTripOperations authorization;
    private final ActivityService activityService;

    public ActivityController(ActivityService activityService, CoreTripOperations authorization) {
        this.authorization = authorization;
        this.activityService = activityService;
    }

    @PostMapping("/trips/{tripId}/destinations/{destinationId}/activities")
    public Activity createActivity(
        @PathVariable Long tripId,
        @PathVariable Long destinationId,
        @Valid @RequestBody Activity activity
    ) {
        return authorization.write(tripId, () -> {
            authorization.destination(tripId, destinationId);
            authorization.newEntity(activity.getId());
            return activityService.createActivity(
                tripId,
                destinationId,
                activity
            );
        });
    }

    @GetMapping("/trips/{tripId}/destinations/{destinationId}/activities")
    public List<Activity> getActivities(
        @PathVariable Long tripId,
        @PathVariable Long destinationId
    ) {
        return authorization.read(tripId, () -> {
            authorization.destination(tripId, destinationId);
            return activityService.getActivities(tripId, destinationId);
        });
    }

    @PutMapping("/trips/{tripId}/destinations/{destinationId}/activities/{activityId}")
    public Activity updateActivity(
        @PathVariable Long tripId,
        @PathVariable Long destinationId,
        @PathVariable Long activityId,
        @Valid @RequestBody Activity updatedActivity
    ) {
        return authorization.write(tripId, () -> {
            authorization.destination(tripId, destinationId);
            authorization.activityAt(tripId, destinationId, activityId);
            return activityService.updateActivity(
                tripId,
                destinationId,
                activityId,
                updatedActivity
            );
        });
    }

    @DeleteMapping("/trips/{tripId}/destinations/{destinationId}/activities/{activityId}")
    public void deleteActivity(
        @PathVariable Long tripId,
        @PathVariable Long destinationId,
        @PathVariable Long activityId
    ) {
        authorization.write(tripId, () -> {
            authorization.destination(tripId, destinationId);
            authorization.activityAt(tripId, destinationId, activityId);
            activityService.deleteActivity(
                tripId,
                destinationId,
                activityId
            );
            return null;
        });
    }
}
