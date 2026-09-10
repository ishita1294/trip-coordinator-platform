package com.ishita.tripcoordinatorplatform.ai;
import com.ishita.tripcoordinatorplatform.model.ItineraryItemFlexibility;

import java.time.LocalDateTime;

/**
 * Contains the itinerary item information needed by the AI
 * when reasoning about how to resolve a conflict.
 */
public class ConflictResolutionItemContext {

    private Long itemId;
    private String title;
    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;
    private ItineraryItemFlexibility flexibility;

    public ConflictResolutionItemContext() {
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

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
}
