package com.ishita.tripcoordinatorplatform.request;

import com.ishita.tripcoordinatorplatform.model.ItineraryItemFlexibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public class CreateItineraryItemRequest {


    @NotBlank
    private String title;

    @NotNull
    private LocalDateTime startDateTime;

    @NotNull
    private LocalDateTime endDateTime;

    private ItineraryItemFlexibility flexibility =
            ItineraryItemFlexibility.FLEXIBLE;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
    }

    public ItineraryItemFlexibility getFlexibility() {
        return flexibility;
    }

    public void setFlexibility(ItineraryItemFlexibility flexibility) {
        this.flexibility = flexibility;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public List<Long> getParticipantIds() {
        return participantIds;
    }

    public void setParticipantIds(List<Long> participantIds) {
        this.participantIds = participantIds;
    }

    private Long activityId;

    private Long reservationId;

    private List<Long> participantIds;
}
