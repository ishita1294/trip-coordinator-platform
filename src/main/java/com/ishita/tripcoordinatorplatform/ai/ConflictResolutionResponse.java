package com.ishita.tripcoordinatorplatform.ai;

import java.util.List;

/**
 * Represents the AI resolver response for one itinerary conflict.
 */
public class ConflictResolutionResponse {

    private List<ConflictResolutionOption> options;

    public ConflictResolutionResponse() {
    }

    public List<ConflictResolutionOption> getOptions() {
        return options;
    }

    public void setOptions(List<ConflictResolutionOption> options) {
        this.options = options;
    }

}
