package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.Activity;
import com.ishita.tripcoordinatorplatform.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ActivityController {
    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping("/trips/{tripId}/destinations/{destinationId}/activities")
    public Activity createActivity(
            @PathVariable Long tripId,
            @PathVariable Long destinationId,
            @Valid @RequestBody Activity activity
    ) {
        return activityService.createActivity(
                tripId,
                destinationId,
                activity
        );
    }

    @GetMapping("/trips/{tripId}/destinations/{destinationId}/activities")
    public List<Activity> getActivities(
            @PathVariable Long tripId,
            @PathVariable Long destinationId
    ) {
        return activityService.getActivities(tripId, destinationId);
    }

    @PutMapping("/trips/{tripId}/destinations/{destinationId}/activities/{activityId}")
    public Activity updateActivity(
            @PathVariable Long tripId,
            @PathVariable Long destinationId,
            @PathVariable Long activityId,
            @Valid @RequestBody Activity updatedActivity
    ) {
        return activityService.updateActivity(
                tripId,
                destinationId,
                activityId,
                updatedActivity
        );
    }

    @DeleteMapping("/trips/{tripId}/destinations/{destinationId}/activities/{activityId}")
    public void deleteActivity(
            @PathVariable Long tripId,
            @PathVariable Long destinationId,
            @PathVariable Long activityId
    ) {
        activityService.deleteActivity(
                tripId,
                destinationId,
                activityId
        );
    }
}
