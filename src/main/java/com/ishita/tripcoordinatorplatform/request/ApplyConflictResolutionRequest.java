package com.ishita.tripcoordinatorplatform.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class ApplyConflictResolutionRequest {

    @NotNull
    private Long itineraryItemId;

    @NotNull
    private LocalDateTime proposedStartDateTime;

    @NotNull
    private LocalDateTime proposedEndDateTime;

    public Long getItineraryItemId() {
        return itineraryItemId;
    }

    public void setItineraryItemId(Long itineraryItemId) {
        this.itineraryItemId = itineraryItemId;
    }

    public LocalDateTime getProposedStartDateTime() {
        return proposedStartDateTime;
    }

    public void setProposedStartDateTime(LocalDateTime proposedStartDateTime) {
        this.proposedStartDateTime = proposedStartDateTime;
    }

    public LocalDateTime getProposedEndDateTime() {
        return proposedEndDateTime;
    }

    public void setProposedEndDateTime(LocalDateTime proposedEndDateTime) {
        this.proposedEndDateTime = proposedEndDateTime;
    }
}