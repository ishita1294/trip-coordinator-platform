package com.ishita.tripcoordinatorplatform.model;


/**
 * Describes how freely an itinerary item's scheduled time can be changed.
 * Used later when generating and validating conflict-resolution options.
 */
public enum ItineraryItemFlexibility {

    // Can usually be moved to another suitable time.
    FLEXIBLE,

    // Can move, but only within known constraints such as opening hours.
    TIME_CONSTRAINED,

    // Should not be moved unless an external reservation/provider confirms a change.
    FIXED
}
